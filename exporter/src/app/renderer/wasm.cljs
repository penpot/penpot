;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.renderer.wasm
  "Headless renderer backend: renders exports with the render-wasm Skia
  pipeline running in this Node process, with no browser and no WebGL.

  Drop-in alternative to `app.renderer.bitmap`/`pdf` (the Playwright
  backends), selected from `app.renderer` when an export is flagged
  headless. Pipeline per request:

    init module (once) -> fetch shape bundle (backend RPC)
      -> serialize scene -> provision fonts -> render each object -> tempfile

  NOTE (current slice): single shared WASM design state, so requests are
  serialized one at a time (no pooling yet). PNG + PDF are wired; jpeg/webp
  still need the PNG->format conversion that `bitmap` does."
  (:require
   ["node:fs" :as fs]
   ["undici" :as http]
   ;; Loaded for side effects: these namespaces register the transit
   ;; read-handlers for penpot file-data types (#penpot/shape, objects-map,
   ;; point, matrix, rect, path, fills). Without them, `get-page` responses
   ;; decode shapes into opaque TaggedValues with nil :id.
   [app.common.geom.matrix]
   [app.common.geom.point]
   [app.common.geom.rect]
   [app.common.types.fills.impl]
   [app.common.types.objects-map]
   [app.common.types.path.impl]
   [app.common.types.shape]
   [app.common.data :as d]
   [app.common.logging :as l]
   [app.common.transit :as t]
   [app.common.types.fills :as types.fills]
   [app.common.uri :as u]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.util.auth :as auth]
   [app.util.mime :as mime]
   [app.util.shell :as sh]
   [app.wasm :as wasm]
   [app.wasm.gfonts :as gfonts]
   [app.wasm.serialize :as serialize]
   [cuerdas.core :as str]
   [promesa.core :as p]))

;; --- module lifecycle (one shared, lazily-initialized instance)

(defonce ^:private module* (atom nil))

(defn- ensure-module!
  []
  (or @module*
      (reset! module* (wasm/init!))))

;; --- serialize access to the single shared WASM module ---
;;
;; There is one shared WASM instance (one design state, one global mem buffer),
;; but `handle-multiple-export` fans out partitions concurrently (`p/all`). This
;; promise queue runs module-touching work one task at a time so concurrent
;; exports can't interleave their serialize/render/alloc on the shared state.

(defonce ^:private queue (atom (p/resolved nil)))

(defn- enqueue!
  "Runs `thunk` (0-arg, returns a promise) only after all previously enqueued
  work has settled. Returns `thunk`'s promise. A task's failure is isolated:
  it doesn't break the chain for the next task."
  [thunk]
  (let [result (p/handle @queue (fn [_ _] (thunk)))]
    (reset! queue (p/handle result (fn [_ _] nil)))
    result))

;; --- shape bundle fetch (backend RPC)

(defn- fetch-objects
  "Fetches the page's `objects` map from the backend via the `get-page` RPC,
  using the same auth the exporter uses elsewhere (management key + bearer)."
  [{:keys [file-id page-id share-id token token-scheme]}]
  (let [agent   (new http/Agent #js {:connect #js {:rejectUnauthorized false}})
        headers #js {"Content-Type"  "application/transit+json"
                     "X-Shared-Key"  (str "exporter " cf/management-key)
                     "Authorization" (auth/header-value token token-scheme)}
        ;; share-id is an OPTIONAL uuid on the backend; it must be omitted when
        ;; absent, not sent as nil (nil fails the uuid schema).
        body    (t/encode-str (cond-> {:file-id file-id
                                       :page-id page-id}
                                share-id (assoc :share-id share-id)))
        uri     (-> (cf/get :public-uri)
                    (u/ensure-path-slash)
                    (u/join "api/rpc/command/get-page")
                    (str))]
    (l/info :hint "wasm render: get-page"
            :uri uri
            :file-id (str file-id)
            :page-id (str page-id)
            :auth-scheme (or token-scheme :bearer)
            :token-len (count (str token)))
    (->> (http/fetch uri #js {:method "POST" :headers headers :body body :dispatcher agent})
         (p/mcat (fn [^js resp]
                   (if (= 200 (.-status resp))
                     (.text resp)
                     ;; Surface the backend's actual error body so we can tell
                     ;; auth (401) from bad params (400/404).
                     (->> (.text resp)
                          (p/mcat (fn [resp-body]
                                    (l/error :hint "wasm render: get-page failed"
                                             :status (.-status resp)
                                             :body resp-body)
                                    (p/rejected (ex-info "get-page failed"
                                                         {:status (.-status resp)
                                                          :body resp-body}))))))))
         (p/fmap t/decode-str)
         (p/fmap :objects))))

;; --- font resolution
;;
;; Custom (team) fonts: the exporter's text serializer keeps their real font
;; uuid, so `wasm/fonts-for-shape` reports it. We fetch the file's team font
;; variants once (get-font-variants RPC), map uuid+weight+style -> ttf asset id,
;; and download the TTF from `assets/by-id/<id>`. Google/builtin fonts still fall
;; back to the bundled default (they need the gfonts catalog — a later slice).

(defn- fetch-font-variants
  "Fetches the file's team (custom) font variants via the get-font-variants RPC.
  Returns a promise of the variant vector (or nil on failure — fonts then just
  fall back, they don't fail the export)."
  [{:keys [file-id share-id token token-scheme]}]
  (let [agent   (new http/Agent #js {:connect #js {:rejectUnauthorized false}})
        headers #js {"Content-Type"  "application/transit+json"
                     "X-Shared-Key"  (str "exporter " cf/management-key)
                     "Authorization" (auth/header-value token token-scheme)}
        body    (t/encode-str (cond-> {:file-id file-id}
                                share-id (assoc :share-id share-id)))
        uri     (-> (cf/get :public-uri)
                    (u/ensure-path-slash)
                    (u/join "api/rpc/command/get-font-variants")
                    (str))]
    (->> (http/fetch uri #js {:method "POST" :headers headers :body body :dispatcher agent})
         (p/mcat (fn [^js resp]
                   (if (= 200 (.-status resp))
                     (.text resp)
                     (p/resolved nil))))
         (p/fmap (fn [s] (when s (t/decode-str s))))
         (p/merr (fn [cause]
                   (l/warn :hint "wasm render: get-font-variants failed" :cause cause)
                   (p/resolved nil))))))

(defn- fetch-asset-bytes
  "Downloads a stored asset (font TTF) by id, returning a promise of an
  ArrayBuffer (or nil)."
  [asset-id {:keys [token token-scheme]}]
  (let [agent   (new http/Agent #js {:connect #js {:rejectUnauthorized false}})
        headers #js {"X-Shared-Key"  (str "exporter " cf/management-key)
                     "Authorization" (auth/header-value token token-scheme)}
        uri     (-> (cf/get :public-uri)
                    (u/ensure-path-slash)
                    (u/join (str "assets/by-id/" asset-id))
                    (str))]
    (->> (http/fetch uri #js {:method "GET" :headers headers :dispatcher agent})
         (p/mcat (fn [^js resp]
                   (if (= 200 (.-status resp))
                     (.arrayBuffer resp)
                     (p/resolved nil))))
         (p/merr (fn [cause]
                   (l/warn :hint "wasm render: font asset fetch failed"
                           :asset-id (str asset-id) :cause cause)
                   (p/resolved nil))))))

(defn- gfont-proxy-url
  "Rewrites a gstatic ttf url to the local gfonts proxy (mirrors the browser's
  `google-font-ttf-url`)."
  [ttf-url]
  (let [proxy (-> (cf/get :public-uri)
                  (u/ensure-path-slash)
                  (u/join "internal/gfonts/font/")
                  (str))]
    (str/replace ttf-url "https://fonts.gstatic.com/s/" proxy)))

(defn- fetch-gfont-bytes
  "Downloads a google font TTF through the local gfonts proxy."
  [ttf-url]
  (let [agent (new http/Agent #js {:connect #js {:rejectUnauthorized false}})
        uri   (gfont-proxy-url ttf-url)]
    (->> (http/fetch uri #js {:method "GET" :dispatcher agent})
         (p/mcat (fn [^js resp]
                   (if (= 200 (.-status resp))
                     (.arrayBuffer resp)
                     (p/resolved nil))))
         (p/merr (fn [cause]
                   (l/warn :hint "wasm render: gfont fetch failed" :url uri :cause cause)
                   (p/resolved nil))))))

(defn- make-resolve-font
  "Builds a `resolve-font` fn (family map -> promise of TTF bytes). Tries the
  file's custom (team) font variants first — matching uuid + weight + style,
  degrading to uuid+weight then uuid — then the shared google catalog. Returns
  nil for fonts not found (builtin falls back to the bundled default)."
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
      (if-let [ttf-id (:ttf-file-id variant)]
        (fetch-asset-bytes ttf-id params)
        (if-let [gurl (gfonts/resolve-ttf-url font-uuid weight style)]
          (fetch-gfont-bytes gurl)
          (p/resolved nil))))))

;; --- image resolution
;;
;; Image fills reference file-media ids. We collect them from the scene, fetch
;; the encoded bytes from `assets/by-file-media-id/<id>`, and hand them to
;; `_store_image` (Skia decodes them; no WebGL). Keyed by media uuid, so this is
;; done once per request rather than per rendered object.

(defn- fetch-file-media-bytes
  "Downloads an image fill's encoded bytes by file-media id."
  [media-id {:keys [token token-scheme]}]
  (let [agent   (new http/Agent #js {:connect #js {:rejectUnauthorized false}})
        headers #js {"X-Shared-Key"  (str "exporter " cf/management-key)
                     "Authorization" (auth/header-value token token-scheme)}
        uri     (-> (cf/get :public-uri)
                    (u/ensure-path-slash)
                    (u/join (str "assets/by-file-media-id/" media-id))
                    (str))]
    (->> (http/fetch uri #js {:method "GET" :headers headers :dispatcher agent})
         (p/mcat (fn [^js resp]
                   (if (= 200 (.-status resp))
                     (.arrayBuffer resp)
                     (p/resolved nil))))
         (p/merr (fn [cause]
                   (l/warn :hint "wasm render: image fetch failed"
                           :media-id (str media-id) :cause cause)
                   (p/resolved nil))))))

(defn- scene-image-ids
  "Distinct image-fill media ids referenced anywhere in the scene."
  [scene]
  (into #{}
        (mapcat (fn [shape]
                  (some-> (:fills shape) not-empty types.fills/coerce types.fills/get-image-ids)))
        (vals scene)))

(defn- provision-images!
  "Fetches and stores every image referenced by the scene's fills."
  [scene params]
  (->> (scene-image-ids scene)
       (map (fn [image-id]
              (->> (fetch-file-media-bytes image-id params)
                   (p/fmap (fn [buf] (when buf (wasm/store-image! image-id buf)))))))
       (p/all)))

(defn- relayout-text!
  "Recomputes layout for every text shape in the scene. Called after fonts are
  provisioned so metrics use the real fonts (serialize-time layout used the
  fallback)."
  [scene]
  (doseq [shape (vals scene)
          :when (= :text (:type shape))]
    (wasm/update-text-layout! (:id shape))))

;; --- render

(defn- render-object-bytes
  [type id scale]
  (case type
    :pdf (let [bytes (wasm/render-shape-pdf id scale)]
           (l/info :hint "PDF generated via Skia (render-wasm headless)"
                   :object-id (str id)
                   :backend "skia-wasm"
                   :bytes (.-length bytes))
           bytes)
    (wasm/render-shape-raster id scale)))

(defn- render*
  [{:keys [scale type objects] :as params} on-object]
  (l/info :hint "wasm render: start" :type type :scale scale :objects (count objects))
  (->> (ensure-module!)
       (p/mcat (fn [_]
                 (l/info :hint "wasm render: module ready, fetching scene"
                         :file-id (str (:file-id params)) :page-id (str (:page-id params)))
                 (fetch-objects params)))
       (p/mcat (fn [scene]
                 (let [sample (first (vals scene))]
                   (l/info :hint "wasm render: scene fetched"
                           :shapes (count scene)
                           :map? (map? scene)
                           :first-key (str (first (keys scene)))
                           :sample-id (str (:id sample))
                           :sample-type (str (:type sample))
                           :sample-keys (pr-str (when (map? sample) (vec (keys sample))))))
                 (serialize/serialize-scene! scene)
                 (l/info :hint "wasm render: scene serialized")
                 ;; Reset the shared module's font store so fonts from a previous
                 ;; request don't accumulate / leak into this one.
                 (wasm/clear-fonts!)
                 ;; Fetch the file's custom (team) font variants once, provision
                 ;; every referenced image once, then resolve/provision fonts per
                 ;; rendered object.
                 (->> (p/all [(fetch-font-variants params)
                              (provision-images! scene params)])
                      (p/mcat
                       (fn [[variants _]]
                         (let [resolve-font (make-resolve-font (or variants []) params)]
                           ;; Provision every object's fonts BEFORE rendering, so
                           ;; the text relayout below sees real font metrics.
                           (p/all (map (fn [{:keys [id]}]
                                         (wasm/provision-fonts! id resolve-font))
                                       objects)))))
                      (p/mcat
                       (fn [_]
                         ;; Serialize-time layout used the fallback font (fonts
                         ;; weren't uploaded yet); recompute now that they are, or
                         ;; text metrics/line breaks are wrong.
                         (relayout-text! scene)
                         (p/run
                          (fn [{:keys [id] :as object}]
                            (let [bytes (render-object-bytes type id scale)
                                  path  (sh/tempfile :prefix "penpot.tmp.wasm."
                                                     :suffix (mime/get-extension type))]
                              (l/info :hint "wasm render: object rendered"
                                      :object-id (str id) :bytes (.-length bytes))
                              (fs/writeFileSync path bytes)
                              ;; `on-object` may return a plain value (zip append
                              ;; returns the archiver instance) or a promise (single
                              ;; export's file move); `p/do` normalizes both to a
                              ;; thenable so `p/mcat` doesn't throw "expected thenable".
                              (p/do (on-object (assoc object :path path)))))
                          objects))))))
       (p/merr (fn [cause]
                 (l/error :hint "wasm render: failed" :cause cause)
                 ;; A panic/abort can leave the shared module's buffer allocated
                 ;; or the wasm instance aborted; drop it so the next request
                 ;; rebuilds a fresh module instead of inheriting the bad state.
                 (reset! module* nil)
                 (p/rejected cause)))))

(defn render
  "Public entry. Serializes module access through `enqueue!` so concurrent
  exports (multi-partition zip) run one at a time on the shared WASM instance."
  [params on-object]
  (enqueue! (fn [] (render* params on-object))))
