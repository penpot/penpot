;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.renderer.wasm
  "Headless renderer backend: renders exports with the render-wasm Skia
  pipeline in this Node process, with no browser and no WebGL.

  Per request: fetch scene (get-page RPC) -> serialize -> provision fonts and
  images -> relayout text with the real fonts -> render each object.

  One shared WASM design state, so requests are serialized one at a time.

  Handles png/jpeg/webp (Skia encodes all three) and pdf; `:svg` stays on the
  browser path."
  (:require
   ["node:fs" :as fs]
   ["undici" :as http]
   [app.common.data :as d]
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
   [app.config :as cf]
   [app.util.mime :as mime]
   [app.util.shell :as sh]
   [app.wasm :as wasm]
   [app.wasm.serialize :as serialize]
   [cuerdas.core :as str]
   [promesa.core :as p]))

;; --- module lifecycle (one shared, lazily-initialized instance)

(defonce ^:private module* (atom nil))

(defn- ensure-module!
  []
  (or @module*
      (reset! module* (wasm/init!))))

;; --- serialized access to the shared module
;;
;; `handle-multiple-export` fans out partitions concurrently, but there is one
;; design state and one global mem buffer, so their serialize/render/alloc must
;; not interleave.

(defonce ^:private queue (atom (p/resolved nil)))

(defn- enqueue!
  "Runs `thunk` (0-arg, returns a promise) only after all previously enqueued
  work has settled. Returns `thunk`'s promise. A task's failure is isolated:
  it doesn't break the chain for the next task."
  [thunk]
  (let [result (p/handle @queue (fn [_ _] (thunk)))]
    (reset! queue (p/handle result (fn [_ _] nil)))
    result))

;; --- backend endpoints
;;
;; Every fetch targets the internal endpoint (falling back to public-uri),
;; in a deployment the exporter reaches the backend over the container network

(defn- internal-uri
  "Absolute URI for `path` on the internal (backend) endpoint."
  [path]
  (-> (cf/get-internal-uri)
      (u/ensure-path-slash)
      (u/join path)
      (str)))

(defn- error-detail
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

(defn- fetch!
  "`undici/fetch` that fails with an ex-info carrying the target uri and the
  unwrapped cause chain, so a failed request says what actually went wrong and
  against which endpoint."
  [uri opts]
  (->> (p/do (http/fetch uri opts))
       (p/merr (fn [cause]
                 (p/rejected (ex-info "http fetch failed"
                                      {:uri uri :detail (error-detail cause)}
                                      cause))))))

(defn- explain
  "Log-friendly reason for `cause`: the detail `fetch!` already attached, or a
  freshly unwrapped chain for anything else (WASM aborts, decode errors)."
  [cause]
  (or (:detail (ex-data cause))
      (error-detail cause)))

(defn- rpc-headers
  "Auth headers for backend RPC calls (management key + bearer)."
  [token]
  #js {"Content-Type"  "application/transit+json"
       "X-Shared-Key"  (str "exporter " cf/management-key)
       "Authorization" (str "Bearer " token)})

(defn- asset-headers
  "Auth headers for `/assets/*`. Cookie, not Bearer: those endpoints redirect to
  a presigned S3/minio URL, and a Bearer header makes S3 400 (\"multiple
  authentication types\")."
  [token]
  #js {"X-Shared-Key" (str "exporter " cf/management-key)
       "Cookie"       (str "auth-token=" token)})

;; --- shape bundle fetch (backend RPC)

(defn- fetch-objects
  "Fetches the exported roots and their children from the backend via the
  `get-page` RPC (`:object-id`, as the browser render path does), using the
  same auth the exporter uses elsewhere (management key + bearer)."
  [{:keys [file-id page-id share-id token objects]}]
  (let [headers (rpc-headers token)
        root-ids (into #{} (map :id) objects)
        body    (t/encode-str (cond-> {:file-id file-id
                                       :page-id page-id}
                                (seq root-ids) (assoc :object-id root-ids)
                                share-id       (assoc :share-id share-id)))
        uri     (internal-uri "api/rpc/command/get-page")]
    (l/dbg :hint "wasm render: get-page"
           :uri uri
           :file-id (str file-id)
           :page-id (str page-id)
           :roots (count root-ids))
    (->> (fetch! uri #js {:method "POST" :headers headers :body body})
         (p/mcat (fn [^js resp]
                   (if (= 200 (.-status resp))
                     (.text resp)
                     (->> (.text resp)
                          (p/mcat (fn [resp-body]
                                    (l/error :hint "wasm render: get-page failed"
                                             :uri uri
                                             :status (.-status resp)
                                             :body resp-body)
                                    (p/rejected (ex-info "get-page failed"
                                                         {:status (.-status resp)
                                                          :body resp-body}))))))))
         (p/fmap t/decode-str)
         (p/fmap :objects))))

;; --- font resolution
;;
;; The text serializer keeps each font's real uuid, so `wasm/fonts-for-shape`
;; reports it. Custom (team) fonts resolve through the file's font variants,
;; google fonts through the shared `app.common.fonts` catalog; builtin
;; fonts through its bundled family + the frontend's static `/fonts/`.

(defn- fetch-font-variants
  "Team (custom) font variants for the file, or nil — a failure here degrades
  to fallback fonts, it does not fail the export."
  [{:keys [file-id share-id token]}]
  (let [headers (rpc-headers token)
        body    (t/encode-str (cond-> {:file-id file-id}
                                share-id (assoc :share-id share-id)))
        uri     (internal-uri "api/rpc/command/get-font-variants")]
    (->> (fetch! uri #js {:method "POST" :headers headers :body body})
         (p/mcat (fn [^js resp]
                   (if (= 200 (.-status resp))
                     (.text resp)
                     (p/resolved nil))))
         (p/fmap (fn [s] (when s (t/decode-str s))))
         (p/merr (fn [cause]
                   (l/warn :hint "wasm render: get-font-variants failed"
                           :uri uri :detail (explain cause) :cause cause)
                   (p/resolved nil))))))

(defn- fetch-ttf-bytes
  "Downloads a TTF, returning a promise of an ArrayBuffer (or nil). A failure
  here degrades to fallback fonts, it does not fail the export."
  ([uri] (fetch-ttf-bytes uri #js {:method "GET"}))
  ([uri opts]
   (->> (fetch! uri opts)
        (p/mcat (fn [^js resp]
                  (if (= 200 (.-status resp))
                    (.arrayBuffer resp)
                    (p/resolved nil))))
        (p/merr (fn [cause]
                  (l/warn :hint "wasm render: font fetch failed"
                          :uri uri :detail (explain cause) :cause cause)
                  (p/resolved nil))))))

;; TTF bytes cached for the process lifetime, keyed by whatever identifies the
;; variant (a gfont id+weight+style, a builtin file name).
(defonce ^:private font-bytes* (atom {}))

(defn- cached-ttf-bytes
  [cache-key fetch-fn]
  (if-let [bytes (get @font-bytes* cache-key)]
    (p/resolved bytes)
    (->> (fetch-fn)
         (p/fmap (fn [buf]
                   (when buf (swap! font-bytes* assoc cache-key buf))
                   buf)))))

(defn- fetch-asset-bytes
  [asset-id {:keys [token]}]
  (fetch-ttf-bytes (internal-uri (str "assets/by-id/" asset-id))
                   #js {:method "GET" :headers (asset-headers token)}))

(defn- fetch-gfont-bytes
  [ttf-url]
  (fetch-ttf-bytes (cfnt/gstatic->proxy-url ttf-url (internal-uri "internal/gfonts/font"))))

(defn- fetch-builtin-font-bytes
  [ttf-file]
  (cached-ttf-bytes ttf-file #(fetch-ttf-bytes (internal-uri (str "fonts/" ttf-file)))))

(defn- make-resolve-font
  "Builds a `resolve-font` fn (family map -> promise of TTF bytes). Custom
  variants first, matching uuid+weight+style then degrading to uuid+weight then
  uuid; the bundled fonts for `uuid/zero`, which is what `font-id->uuid` maps
  every builtin family to; google catalog otherwise."
  [variants params]
  (fn [{:keys [id weight style]}]
    (let [font-uuid (uuid/from-unsigned-parts (aget id 0) (aget id 1) (aget id 2) (aget id 3))
          style-str (if (zero? style) "normal" "italic")
          variant   (or (d/seek (fn [v] (and (= (:font-id v) font-uuid)
                                             (= (:font-weight v) weight)
                                             (= (name (:font-style v)) style-str)))
                                variants)
                        (d/seek (fn [v] (and (= (:font-id v) font-uuid)
                                             (= (:font-weight v) weight)))
                                variants)
                        (d/seek (fn [v] (= (:font-id v) font-uuid)) variants))]
      (cond
        (:ttf-file-id variant)
        (fetch-asset-bytes (:ttf-file-id variant) params)

        (= uuid/zero font-uuid)
        (fetch-builtin-font-bytes (cfnt/resolve-ttf-file weight style))

        :else
        (if-let [gurl (cfnt/resolve-ttf-url font-uuid weight style)]
          (fetch-gfont-bytes gurl)
          (p/resolved nil))))))

;; --- fallback fonts (emoji + per-script noto fonts)
;;
;; Emoji and non-latin scripts render through fallback families, not through
;; any span's font family, so `wasm/fonts-for-shape` never reports them and the
;; provisioning above never uploads them. Must run per request, since
;; `clear-fonts!` empties the store; the TTF bytes stay cached per process.

(defn- scene-fallback-fonts
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

(defn- fetch-fallback-font-bytes
  "Downloads one fallback font's TTF. Cached by the whole variant, not just
  `font-id`: `resolve-ttf-url` picks a different TTF per weight/style, so a
  font-id-only key would serve the first downloaded variant for every other one."
  [{:keys [font-id weight style]}]
  (if-let [ttf-url (some-> (cfnt/gfont-id->uuid font-id) (cfnt/resolve-ttf-url weight style))]
    (cached-ttf-bytes [font-id weight style] #(fetch-gfont-bytes ttf-url))
    (p/resolved nil)))

(defn- provision-fallback-fonts!
  [scene]
  (->> (scene-fallback-fonts scene)
       (map (fn [{:keys [font-id weight style is-emoji is-fallback] :as font}]
              (if-let [font-uuid (cfnt/gfont-id->uuid font-id)]
                (->> (fetch-fallback-font-bytes font)
                     (p/fmap (fn [buf]
                               (if buf
                                 (wasm/store-font! {:id (uuid/get-u32 font-uuid)
                                                    :weight weight
                                                    :style style
                                                    :emoji? (boolean is-emoji)
                                                    :fallback? (boolean is-fallback)}
                                                   buf)
                                 (l/warn :hint "wasm render: fallback font unavailable"
                                         :font-id font-id)))))
                (p/resolved nil))))
       (p/all)))

;; --- image resolution
;;
;; Image fills reference file-media ids; the encoded bytes go straight to
;; `_store_image` (Skia decodes, no WebGL), keyed by media uuid so this happens
;; once per request rather than per rendered object.

(defn- fetch-file-media-bytes
  "Downloads an image fill's encoded bytes by file-media id."
  [media-id {:keys [token]}]
  (let [headers (asset-headers token)
        uri     (internal-uri (str "assets/by-file-media-id/" media-id))]
    (->> (fetch! uri #js {:method "GET" :headers headers})
         (p/mcat (fn [^js resp]
                   (if (= 200 (.-status resp))
                     (.arrayBuffer resp)
                     (do
                       (l/warn :hint "wasm render: image fetch non-200"
                               :media-id (str media-id)
                               :uri uri
                               :status (.-status resp))
                       (p/resolved nil)))))
         (p/merr (fn [cause]
                   (l/warn :hint "wasm render: image fetch failed"
                           :media-id (str media-id) :uri uri
                           :detail (explain cause) :cause cause)
                   (p/resolved nil))))))

(defn- provision-images!
  "Fetches and stores every image the scene references (shape, stroke and
  text-span fills, enumerated by `app.common.types.shape.images`). Unlike fonts,
  the image store is not reset per request, so already-held images are skipped
  and repeated exports of a file reuse them."
  [scene params]
  (let [all-ids (images/scene-image-ids scene)
        new-ids (remove wasm/image-cached? all-ids)]
    (l/dbg :hint "wasm render: provisioning images"
           :total (count all-ids)
           :cached (- (count all-ids) (count new-ids)))
    (->> new-ids
         (map (fn [image-id]
                (->> (fetch-file-media-bytes image-id params)
                     (p/fmap (fn [buf]
                               (if buf
                                 (do
                                   (l/dbg :hint "wasm render: image stored"
                                          :media-id (str image-id)
                                          :bytes (.-byteLength ^js buf))
                                   (wasm/store-image! image-id buf))
                                 (l/warn :hint "wasm render: image unavailable"
                                         :media-id (str image-id))))))))
         (p/all))))

(defn- relayout-text!
  "Recomputes layout for every text shape, once the real fonts are provisioned
  (serialize-time layout used the fallback)."
  [scene]
  (doseq [shape (vals scene)
          :when (= :text (:type shape))]
    (wasm/update-text-layout! (:id shape))))

;; --- render

(defn- render-object-bytes
  [type id scale]
  (if (= :pdf type)
    (let [bytes (wasm/render-shape-pdf id scale)]
      (l/dbg :hint "PDF generated via Skia (render-wasm headless)"
             :object-id (str id)
             :backend "skia-wasm"
             :bytes (.-length bytes))
      bytes)
    (wasm/render-shape-raster id scale type)))

(defn- render*
  [{:keys [scale type objects] :as params} on-object]
  (l/dbg :hint "wasm render: start"
         :type type
         :scale scale
         :objects (count objects)
         :file-id (str (:file-id params))
         :page-id (str (:page-id params)))
  (->> (ensure-module!)
       (p/mcat (fn [_] (fetch-objects params)))
       (p/mcat (fn [scene]
                 (l/dbg :hint "wasm render: scene fetched" :shapes (count scene))
                 (serialize/serialize-scene! scene)
                 (l/dbg :hint "wasm render: scene serialized")
                 ;; So fonts from a previous request don't leak into this one.
                 (wasm/clear-fonts!)
                 (->> (p/all [(fetch-font-variants params)
                              (provision-images! scene params)
                              (provision-fallback-fonts! scene)])
                      (p/mcat
                       (fn [[variants _]]
                         (let [resolve-font (make-resolve-font (or variants []) params)]
                           ;; Before rendering, so the relayout below sees real
                           ;; font metrics. Deduped across objects: a partition
                           ;; sharing one family downloads its TTF once.
                           (wasm/provision-fonts! (map :id objects) resolve-font))))
                      (p/mcat
                       (fn [_]
                         (relayout-text! scene)
                         (p/run
                          (fn [{:keys [id] :as object}]
                            (let [bytes (render-object-bytes type id scale)
                                  path  (sh/tempfile :prefix "penpot.tmp.wasm."
                                                     :suffix (mime/get-extension type))]
                              (l/dbg :hint "wasm render: object rendered"
                                     :object-id (str id) :bytes (.-length bytes))
                              (fs/writeFileSync path bytes)
                              ;; `on-object` returns a plain value (zip append) or
                              ;; a promise (single export's file move); `p/do`
                              ;; normalizes both to a thenable.
                              (p/do (on-object (assoc object :path path)))))
                          objects))))))
       (p/fmap (fn [result]
                 ;; After the request, never mid-render, so an image can't
                 ;; disappear under a running export.
                 (let [evicted (wasm/evict-images! wasm/image-cache-mb)]
                   (when (pos? evicted)
                     (l/info :hint "wasm render: evicted cached images" :count evicted)))
                 result))
       (p/merr (fn [cause]
                 (l/error :hint "wasm render: failed"
                          :detail (explain cause)
                          :internal-uri (str (cf/get-internal-uri))
                          :cause cause)
                 ;; A panic can leave the mem buffer allocated or the instance
                 ;; aborted; drop it so the next request rebuilds a fresh one.
                 (reset! module* nil)
                 (p/rejected cause)))))

(defn render
  "Public entry. `enqueue!` keeps concurrent exports off each other's toes on
  the shared WASM instance."
  [params on-object]
  (enqueue! (fn [] (render* params on-object))))
