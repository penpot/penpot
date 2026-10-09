;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns exporter.wasm.render
  "Headless render pipeline: renders exports with the render-wasm Skia pipeline,
  with no browser and no WebGL.

  Per request: fetch scene (get-page RPC) -> serialize -> provision fonts and
  images -> relayout text with the real fonts -> render each object.

  This runs inside a render worker (one WASM design state per worker), so the
  synchronous Skia calls never block the main thread. Everything the pipeline
  needs beyond the export itself travels in `env`
  (`:internal-uri`, `:public-uri`, `:management-key`, `:tmpdir`,
  `:image-cache-size`): the worker thread reads no config and no globals.
  There are no promesa chains in this namespace.

  Handles png/jpeg/webp (Skia encodes all three), pdf and svg."
  (:require
   ["node:fs" :as fs]
   ["undici" :as http]
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.fonts :as cfnt]
   ;; Required for side effects: these register the transit read handlers and
   ;; deftype impls the `get-page` response is decoded into.
   [app.common.geom.matrix]
   [app.common.geom.point]
   [app.common.geom.rect]
   [app.common.logging :as l]
   [app.common.transit :as t]
   [app.common.types.fills.impl]
   [app.common.types.objects-map]
   [app.common.types.path.impl]
   [app.common.types.shape]
   [app.common.types.shape.images :as images]
   [app.common.uri :as u]
   [app.common.uuid :as uuid]
   [cuerdas.core :as str]
   [exporter.shell :as shell]
   [exporter.util.mime :as mime]
   [exporter.wasm :as wasm]
   [exporter.wasm.serialize :as serialize]))

(def fetch
  "The network entry point of the pipeline: a plain def, redefined by
  the tests, which never need a network."
  http/fetch)

;; --- module lifecycle (one shared, lazily-initialized instance)

(defonce ^:private module* (atom nil))

(defn- ^:async ensure-module
  []
  (or @module*
      (reset! module* (await (wasm/init)))))

;; --- backend endpoints
;;
;; Every fetch targets the internal endpoint (falling back to public-uri),
;; in a deployment the exporter reaches the backend over the container network

(defn internal-uri
  "Absolute URI for `path` on the internal (backend) endpoint."
  [env path]
  (-> (:internal-uri env)
      (u/ensure-path-slash)
      (u/join path)
      (str)))

(defn public-uri
  "Absolute URI for `path` on the public endpoint. Whoever opens an exported SVG
  resolves its `@font-face` sources, so those cannot use the internal endpoint."
  [env path]
  (-> (:public-uri env)
      (u/ensure-path-slash)
      (u/join path)
      (str)))

(defn error-detail
  "Node's fetch reports every transport failure as a bare `TypeError: fetch
  failed`; the actual reason (TLS rejection, DNS, ECONNREFUSED) is buried in a
  nested `cause` chain that the logger does not print. Flattens the chain into
  one readable string."
  [cause]
  (->> (iterate (fn [^js e] (unchecked-get e "cause")) cause)
       (take-while some?)
       (take 5)
       (map (fn [^js e]
              (let [code (unchecked-get e "code")
                    msg  (or (unchecked-get e "message") (str e))]
                (if code (str code ": " msg) msg))))
       (str/join " <- ")))

(defn- ^:async request
  "`undici/fetch` that fails with an ex-info carrying the target uri and the
  unwrapped cause chain, so a failed request says what actually went wrong and
  against which endpoint."
  [uri opts]
  (try
    (await (fetch uri opts))
    (catch :default cause
      (throw (ex-info "http fetch failed"
                      {:uri uri :detail (error-detail cause)}
                      cause)))))

(defn- explain
  "Log-friendly reason for `cause`: the detail `request` already attached, or a
  freshly unwrapped chain for anything else (WASM aborts, decode errors)."
  [cause]
  (or (:detail (ex-data cause))
      (error-detail cause)))

(defn- rpc-headers
  "Auth headers for backend RPC calls (management key + bearer)."
  [env token]
  #js {"Content-Type"  "application/transit+json"
       "X-Shared-Key"  (str "exporter " (:management-key env))
       "Authorization" (str "Bearer " token)})

(defn- asset-headers
  "Auth headers for `/assets/*`. Cookie, not Bearer: those endpoints redirect to
  a presigned S3/minio URL, and a Bearer header makes S3 400 (\"multiple
  authentication types\")."
  [env token]
  #js {"X-Shared-Key" (str "exporter " (:management-key env))
       "Cookie"       (str "auth-token=" token)})

;; --- shape bundle fetch (backend RPC)

(defn ^:async fetch-objects
  "Fetches the exported roots and their children from the backend via the
  `get-page` RPC (`:object-id`, as the browser render path does), using the
  same auth the exporter uses elsewhere (management key + bearer)."
  [env {:keys [file-id page-id share-id token objects]}]
  (let [headers  (rpc-headers env token)
        root-ids (into #{} (map :id) objects)
        body     (t/encode-str (cond-> {:file-id file-id
                                        :page-id page-id}
                                 (seq root-ids) (assoc :object-id root-ids)
                                 share-id       (assoc :share-id share-id)))
        uri      (internal-uri env "api/rpc/command/get-page")]
    (l/dbg :hint "wasm render: get-page"
           :uri uri
           :file-id (str file-id)
           :page-id (str page-id)
           :roots (count root-ids))
    (let [resp (await (request uri #js {:method "POST" :headers headers :body body}))]
      (if (= 200 (.-status resp))
        (:objects (t/decode-str (await (.text resp))))
        (let [resp-body (await (.text resp))]
          (l/error :hint "wasm render: get-page failed"
                   :uri uri
                   :status (.-status resp)
                   :body resp-body)
          (throw (ex-info "get-page failed"
                          {:status (.-status resp)
                           :body resp-body})))))))

;; --- font resolution
;;
;; The text serializer keeps each font's real uuid, so `wasm/fonts-for-shape`
;; reports it. Custom (team) fonts resolve through the file's font variants,
;; google fonts through the shared `app.common.fonts` catalog; builtin
;; fonts through its bundled family + the frontend's static `/fonts/`.

(defn ^:async fetch-font-variants
  "Team (custom) font variants for the file, or nil — a failure here degrades
  to fallback fonts, it does not fail the export."
  [env {:keys [file-id share-id token]}]
  (let [headers (rpc-headers env token)
        body    (t/encode-str (cond-> {:file-id file-id}
                                share-id (assoc :share-id share-id)))
        uri     (internal-uri env "api/rpc/command/get-font-variants")]
    (try
      (let [resp (await (request uri #js {:method "POST" :headers headers :body body}))]
        (when (= 200 (.-status resp))
          (t/decode-str (await (.text resp)))))
      (catch :default cause
        (l/warn :hint "wasm render: get-font-variants failed"
                :uri uri :detail (explain cause) :cause cause)
        nil))))

(defn- ^:async fetch-ttf-bytes
  "Downloads a TTF (an ArrayBuffer, or nil). A failure here degrades to
  fallback fonts, it does not fail the export."
  [uri opts]
  (try
    (let [resp (await (request uri opts))]
      (when (= 200 (.-status resp))
        (await (.arrayBuffer resp))))
    (catch :default cause
      (l/warn :hint "wasm render: font fetch failed"
              :uri uri :detail (explain cause) :cause cause)
      nil)))

;; TTF bytes cached for the process lifetime, keyed by whatever identifies the
;; variant (a gfont id+weight+style, a builtin file name).
(defonce ^:private font-bytes* (atom {}))

(defn- ^:async cached-ttf-bytes
  [cache-key fetch-fn]
  (if-let [bytes (get @font-bytes* cache-key)]
    bytes
    (let [buf (await (fetch-fn))]
      (when buf (swap! font-bytes* assoc cache-key buf))
      buf)))

(defn- ^:async fetch-asset-bytes
  [env asset-id token]
  (fetch-ttf-bytes (internal-uri env (str "assets/by-id/" asset-id))
                   #js {:method "GET" :headers (asset-headers env token)}))

(defn- ^:async fetch-gfont-bytes
  [env ttf-url]
  (fetch-ttf-bytes (cfnt/gstatic->proxy-url ttf-url (internal-uri env "internal/gfonts/font"))
                   #js {:method "GET"}))

(defn- ^:async fetch-builtin-font-bytes
  [env ttf-file]
  (cached-ttf-bytes ttf-file #(fetch-ttf-bytes (internal-uri env (str "fonts/" ttf-file))
                                               #js {:method "GET"})))

(defn family-uuid
  [id]
  (uuid/from-unsigned-parts (aget id 0) (aget id 1) (aget id 2) (aget id 3)))

(defn find-variant
  "Custom variant for a family: uuid+weight+style first, degrading to
  uuid+weight then uuid."
  [variants font-uuid weight style]
  (let [style-str (if (zero? style) "normal" "italic")]
    (or (d/seek (fn [v] (and (= (:font-id v) font-uuid)
                             (= (:font-weight v) weight)
                             (= (name (:font-style v)) style-str)))
                variants)
        (d/seek (fn [v] (and (= (:font-id v) font-uuid)
                             (= (:font-weight v) weight)))
                variants)
        (d/seek (fn [v] (= (:font-id v) font-uuid)) variants))))

(defn- make-resolve-font
  "Builds a `resolve-font` fn (family map -> TTF bytes). Custom
  variants first; the bundled fonts for `uuid/zero`, which is what
  `font-id->uuid` maps every builtin family to; google catalog otherwise."
  [env variants params]
  (^:async fn [{:keys [id weight style]}]
    (let [font-uuid (family-uuid id)
          variant   (find-variant variants font-uuid weight style)]
      (cond
        (:ttf-file-id variant)
        (await (fetch-asset-bytes env (:ttf-file-id variant) params))

        (= uuid/zero font-uuid)
        (await (fetch-builtin-font-bytes env (cfnt/resolve-ttf-file weight style)))

        :else
        (if-let [gurl (cfnt/resolve-ttf-url font-uuid weight style)]
          (await (fetch-gfont-bytes env gurl))
          nil)))))

(defn- make-font-url
  "Builds a `font-url` fn (family map -> public URL of its TTF), the same
  sources `make-resolve-font` downloads from but addressed publicly. The SVG
  export emits one `@font-face` per family from these."
  [env variants]
  (fn [{:keys [id weight style]}]
    (let [font-uuid (family-uuid id)
          variant   (find-variant variants font-uuid weight style)]
      (cond
        (:ttf-file-id variant)
        (public-uri env (str "assets/by-id/" (:ttf-file-id variant)))

        (= uuid/zero font-uuid)
        (public-uri env (str "fonts/" (cfnt/resolve-ttf-file weight style)))

        :else
        (some-> (cfnt/resolve-ttf-url font-uuid weight style)
                (cfnt/gstatic->proxy-url (public-uri env "internal/gfonts/font")))))))

;; --- fallback fonts (emoji + per-script noto fonts)
;;
;; Emoji and non-latin scripts render through fallback families, not through
;; any span's font family, so `wasm/fonts-for-shape` never reports them and the
;; provisioning above never uploads them. Must run per request, since
;; `clear-fonts` empties the store; the TTF bytes stay cached per process.

(defn scene-fallback-fonts
  "Fallback font descriptors needed by the scene's text. Deduped because
  several languages map to one noto family and provisioning is concurrent —
  otherwise they all miss the byte cache at once and refetch the same TTF."
  [scene]
  (let [texts  (for [shape (vals scene)
                     :when (= :text (:type shape))
                     node  (or (some->> (:content shape) (tree-seq :children :children)) [])
                     :let  [text (:text node)]
                     :when (string? text)]
                 text)
        emoji? (boolean (some cfnt/contains-emoji? texts))
        langs  (reduce cfnt/collect-used-languages #{} texts)]
    (distinct
     (cond-> (cfnt/add-noto-fonts [] langs)
       emoji? (cfnt/add-emoji-font)))))

(defn- ^:async fetch-fallback-font-bytes
  "Downloads one fallback font's TTF. Cached by the whole variant, not just
  `font-id`: `resolve-ttf-url` picks a different TTF per weight/style, so a
  font-id-only key would serve the first downloaded variant for every other one."
  [env {:keys [font-id weight style]}]
  (if-let [ttf-url (some-> (cfnt/gfont-id->uuid font-id) (cfnt/resolve-ttf-url weight style))]
    (cached-ttf-bytes [font-id weight style] #(fetch-gfont-bytes env ttf-url))
    nil))

(defn- ^:async provision-fallback-fonts
  [env scene]
  (await (js/Promise.all
          (mapv (^:async fn [{:keys [font-id weight style is-emoji is-fallback] :as font}]
                  (if-let [font-uuid (cfnt/gfont-id->uuid font-id)]
                    (let [buf (await (fetch-fallback-font-bytes env font))]
                      (if buf
                        (wasm/store-font {:id (uuid/get-u32 font-uuid)
                                          :weight weight
                                          :style style
                                          :emoji? (boolean is-emoji)
                                          :fallback? (boolean is-fallback)}
                                         buf)
                        (l/warn :hint "wasm render: fallback font unavailable"
                                :font-id font-id)))
                    nil))
                (scene-fallback-fonts scene))))
  nil)

;; --- image resolution
;;
;; Image fills reference file-media ids; the encoded bytes go straight to
;; `_store_image` (Skia decodes, no WebGL), keyed by media uuid so this happens
;; once per request rather than per rendered object.

(defn ^:async fetch-file-media-bytes
  "Downloads an image fill's encoded bytes by file-media id, or nil when
  they cannot be had: the object renders without its image either way."
  [env media-id token]
  (let [headers (asset-headers env token)
        uri     (internal-uri env (str "assets/by-file-media-id/" media-id))]
    (try
      (let [resp (await (request uri #js {:method "GET" :headers headers}))]
        (if (= 200 (.-status resp))
          (await (.arrayBuffer resp))
          (do
            (l/warn :hint "wasm render: image fetch non-200"
                    :media-id (str media-id)
                    :uri uri
                    :status (.-status resp))
            nil)))
      (catch :default cause
        (l/warn :hint "wasm render: image fetch failed"
                :media-id (str media-id) :uri uri
                :detail (explain cause) :cause cause)
        nil))))

(defn- ^:async provision-images
  "Fetches and stores every image the scene references (shape, stroke and
  text-span fills, enumerated by `app.common.types.shape.images`). Unlike fonts,
  the image store is not reset per request, so already-held images are skipped
  and repeated exports of a file reuse them.

  Always registers a public media URL for each id so SVG export can emit linked
  `<image href>` even when the encoded bytes were already cached."
  [env scene params]
  (let [all-ids (images/scene-image-ids scene)
        new-ids (remove wasm/image-cached? all-ids)]
    (l/dbg :hint "wasm render: provisioning images"
           :total (count all-ids)
           :cached (- (count all-ids) (count new-ids)))
    (doseq [image-id all-ids]
      (wasm/store-image-url image-id (public-uri env (str "assets/by-file-media-id/" image-id))))
    (await (js/Promise.all
            (mapv (^:async fn [image-id]
                    (let [buf (await (fetch-file-media-bytes env image-id (:token params)))]
                      (if buf
                        (do
                          (l/dbg :hint "wasm render: image stored"
                                 :media-id (str image-id)
                                 :bytes (.-byteLength ^js buf))
                          (wasm/store-image image-id buf))
                        (l/warn :hint "wasm render: image unavailable"
                                :media-id (str image-id))))
                    nil)
                  new-ids)))
    nil))

(defn- relayout-text
  "Recomputes layout for every text shape, once the real fonts are provisioned
  (serialize-time layout used the fallback)."
  [scene]
  (doseq [shape (vals scene)
          :when (= :text (:type shape))]
    (wasm/update-text-layout (:id shape))))

;; --- render

(defn- check-cancelled
  "Cancellation is cooperative: a render already inside Skia cannot be
  interrupted, so the flag is only observed between objects. Killing a job
  mid-object is the caller's job (terminating the worker)."
  [{:keys [cancelled?] :as _params}]
  (when (and cancelled? (cancelled?))
    (ex/raise :type :internal
              :code :job-cancelled
              :hint "export job was cancelled")))

(defn- render-object-bytes
  [type id scale]
  (case type
    :pdf (let [bytes (wasm/render-shape-pdf id scale)]
           (l/dbg :hint "PDF generated via Skia (render-wasm headless)"
                  :object-id (str id)
                  :backend "skia-wasm"
                  :bytes (.-length bytes))
           bytes)
    :svg (let [bytes (wasm/render-shape-svg id scale)]
           (l/dbg :hint "SVG generated via Skia (render-wasm headless)"
                  :object-id (str id)
                  :backend "skia-wasm"
                  :bytes (.-length bytes))
           bytes)
    (wasm/render-shape-raster id scale type)))

(defn- ^:async render*
  [env {:keys [scale type objects] :as params} on-object]
  (l/dbg :hint "wasm render: start"
         :type type
         :scale scale
         :objects (count objects)
         :file-id (str (:file-id params))
         :page-id (str (:page-id params)))
  (try
    (await (ensure-module))
    (let [scene (await (fetch-objects env params))]
      (l/dbg :hint "wasm render: scene fetched" :shapes (count scene))
      (serialize/serialize-scene scene)
      (l/dbg :hint "wasm render: scene serialized")
      ;; So fonts from a previous request don't leak into this one.
      (wasm/clear-fonts)
      (let [[variants] (await (js/Promise.all [(fetch-font-variants env params)
                                               (provision-images env scene params)
                                               (provision-fallback-fonts env scene)]))
            variants     (or variants [])
            resolve-font (make-resolve-font env variants params)
            font-url     (make-font-url env variants)]
        ;; Before rendering, so the relayout below sees real
        ;; font metrics. Deduped across objects: shapes
        ;; sharing one family download its TTF once.
        (await (wasm/provision-fonts (map :id objects) resolve-font
                                     :font-url font-url)))
      (relayout-text scene)
      ;; Sequential, like the synchronous Skia calls underneath:
      ;; the cancel check lands between objects.
      (doseq [{:keys [id] :as object} objects]
        (check-cancelled params)
        (let [bytes (render-object-bytes type id scale)
              path  (shell/tempfile (:tmpdir env)
                                    :prefix "penpot.tmp.wasm."
                                    :suffix (mime/get-extension type))]
          (l/dbg :hint "wasm render: object rendered"
                 :object-id (str id) :bytes (.-length bytes))
          (fs/writeFileSync path bytes)
          (await (on-object (assoc object :path path)))))
      ;; After the request, never mid-render, so an image can't
      ;; disappear under a running export.
      (let [evicted (wasm/evict-images (:image-cache-size env))]
        (when (pos? evicted)
          (l/info :hint "wasm render: evicted cached images" :count evicted)))
      nil)
    (catch :default cause
      (l/error :hint "wasm render: failed"
               :detail (explain cause)
               :internal-uri (str (:internal-uri env))
               :cause cause)
      ;; A panic can leave the mem buffer allocated or the instance
      ;; aborted; drop it so the next request rebuilds a fresh one.
      (reset! module* nil)
      (throw cause))))

(defn ^:async render
  "Public entry. Renders every object of `params` with the render
  environment `env` (`:internal-uri`, `:public-uri`, `:management-key`,
  `:tmpdir`, `:image-cache-size`), calling `on-object` with
  `{:id :filename :path ...}` as each one is written out."
  [env params on-object]
  (await (render* env params on-object)))
