;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.thumbnails-wasm
  "WASM-based component thumbnail rendering.
   Renders component previews using the existing workspace WASM context
   via render-shape-pixels (SurfaceId::Export), avoiding a separate
   WASM module in the worker.

   Two-phase design:
   - render-thumbnail: immediate WASM render → UI update (no server)
   - persist-thumbnail: pushes current data-uri to the server (debounced)"
  (:require
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.common.logging :as l]
   [app.common.math :as mth]
   [app.common.thumbnails :as thc]
   [app.common.time :as ct]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.thumbnails :as dwt]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.render-wasm.api :as wasm.api]
   [app.util.timers :as timers]
   [app.util.webapi :as wapi]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]
   [potok.v2.core :as ptk]))

(l/set-level! :warn)

(defn- png-bytes->data-uri
  "Converts a Uint8Array of PNG bytes to a data:image/png;base64 URI."
  [png-bytes]
  (let [blob (wapi/create-blob png-bytes "image/png")
        reader (js/FileReader.)]
    (js/Promise.
     (fn [resolve reject]
       (set! (.-onload reader)
             (fn [] (resolve (.-result reader))))
       (set! (.-onerror reader)
             (fn [e] (reject e)))
       (.readAsDataURL reader blob)))))

;; This constant stores the target thumbnail minimum max-size so
;; the images doesn't lose quality when rendered
(def ^:private ^:const target-size 200)

(defn- render-component-pixels
  "Renders a component frame using the workspace WASM context.
   Returns an observable that emits a data-uri string.
   Deferred by one animation frame so that process-shape-changes!
   has time to sync all child shapes to WASM memory first."
  [file-id page-id frame-id]
  (rx/create
   (fn [subs]
     (let [req-id
           (timers/raf
            (fn [_]
              (try
                (let [objects (dsh/lookup-page-objects @st/state file-id page-id)]
                  (if-let [frame (get objects frame-id)]
                    (let [{ext-w :width ext-h :height} (wasm.api/get-shape-extrect frame-id)
                          {sel-w :width sel-h :height} (:selrect frame)
                          max-size (mth/max (or ext-w sel-w) (or ext-h sel-h))
                          scale (mth/max 1 (/ target-size max-size))
                          png-bytes (wasm.api/render-shape-pixels frame-id scale)]
                      (if (or (nil? png-bytes) (zero? (.-length png-bytes)))
                        (do
                          (l/error :hint "render-shape-pixels returned empty" :frame-id (str frame-id))
                          (rx/end! subs))
                        (.then
                         (png-bytes->data-uri png-bytes)
                         (fn [data-uri]
                           (rx/push! subs data-uri)
                           (rx/end! subs))
                         (fn [err]
                           (rx/error! subs err)))))

                    (rx/error! subs "Frame not found")))
                (catch :default err
                  (rx/error! subs err)))))]
       #(timers/cancel-af! req-id)))))

(defn render-thumbnail
  "Renders a component thumbnail via WASM and updates the UI immediately.
   When `persist?` is true, also persists the rendered thumbnail to the
   server in the same observable chain (guaranteeing correct ordering)."
  [file-id page-id frame-id & {:keys [persist?] :or {persist? false}}]

  (let [object-id (thc/fmt-object-id file-id page-id frame-id "component")]
    (ptk/reify ::render-thumbnail
      cljs.core/IDeref
      (-deref [_] object-id)

      ptk/WatchEvent
      (watch [_ state stream]
        ;; When the component is removed it can arrived a render
        ;; request with frame-id=null
        (when (some? frame-id)
          (letfn [(load-objects-stream
                    []
                    (rx/create
                     (fn [subs]
                       (let [objects (dsh/lookup-page-objects state file-id page-id)

                             ;; retrieves a subtree with only the id and its children
                             ;; to be loaded before rendering the thumbnail
                             subtree
                             (into {}
                                   (map #(vector (:id %) %))
                                   (cfh/get-children-with-self objects frame-id))]
                         (try
                           (wasm.api/set-objects subtree #(rx/push! subs %))
                           (catch :default err
                             (rx/error! subs err)))))))

                  (persist-to-server
                    [data-uri]
                    (let [blob (wapi/data-uri->blob data-uri)]
                      (->> (rp/cmd! :create-file-object-thumbnail
                                    {:file-id file-id
                                     :object-id object-id
                                     :media blob
                                     :tag "component"})
                           (rx/catch rx/empty)
                           (rx/ignore))))

                  (do-render-thumbnail
                    []
                    (let [tp (ct/tpoint-ms)]
                      (->> (render-component-pixels file-id page-id frame-id)
                           (rx/mapcat
                            (fn [data-uri]
                              (l/dbg :hint "component thumbnail rendered (wasm)"
                                     :elapsed (dm/str (tp) "ms"))
                              (if persist?
                                (rx/merge
                                 (rx/of (dwt/assoc-thumbnail object-id data-uri))
                                 (persist-to-server data-uri))
                                (rx/of (dwt/assoc-thumbnail object-id data-uri)))))

                           (rx/catch (fn [err]
                                       (js/console.error err)
                                       (l/error :hint "error rendering component thumbnail" :frame-id (str frame-id))
                                       (rx/empty)))

                           (rx/take-until
                            (->> stream
                                 (rx/filter (ptk/type? ::dwt/clear-thumbnail))
                                 (rx/filter #(= (deref %) object-id)))))))]

            (if (not= page-id (:current-page-id state))
              (->> (load-objects-stream)
                   (rx/mapcat do-render-thumbnail))
              (do-render-thumbnail))))))))

(defn persist-thumbnail
  "Persists the current component thumbnail data-uri to the server.
   Expects that `render-thumbnail` has already been called so the
   data-uri is present in app state. If not, this is a no-op."
  [file-id page-id frame-id]
  (let [object-id (thc/fmt-object-id file-id page-id frame-id "component")]
    (ptk/reify ::persist-thumbnail
      ptk/WatchEvent
      (watch [_ state _]
        (let [data-uri (dm/get-in state [:thumbnails object-id])]
          (if (and (some? data-uri)
                   (str/starts-with? data-uri "data:"))
            (let [blob (wapi/data-uri->blob data-uri)]
              (->> (rp/cmd! :create-file-object-thumbnail
                            {:file-id file-id
                             :object-id object-id
                             :media blob
                             :tag "component"})
                   (rx/catch rx/empty)
                   (rx/ignore)))
            (rx/empty)))))))
