;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.worker.exports
  "Local WASM export (single file, ZIP, multi-page PDF) on the worker module.

  Runs through `app.worker.wasm/submit` so it never interleaves with
  thumbnail WASM jobs on the same module."
  (:require
   [app.common.logging :as log]
   [app.common.media :as media]
   [app.common.render-wasm.wasm :as wasm]
   [app.common.types.color :as cc]
   [app.common.uuid :as uuid]
   [app.main.data.exports.wasm :as wasm.exports]
   [app.render-wasm.api :as wasm.api]
   [app.util.webapi :as wapi]
   [app.util.zip :as uz]
   [app.worker.impl :as impl]
   [app.worker.wasm :as wwasm]
   [beicon.v2.core :as rx]
   [promesa.core :as p]))

(log/set-level! :info)

(def ^:private ^:const canvas-size 100)

(defn- export-cancelled
  []
  (ex-info "export cancelled" {:type :export-cancelled}))

(defn- export-mtype
  [type]
  (case type
    :pdf "application/pdf"
    (media/format->mtype type)))

(defn- build-single
  "One shape → one downloadable blob (PNG/JPEG/WebP/SVG/PDF)."
  [exports job-id on-progress]
  (let [export (first exports)]
    (when-not export
      (throw (ex-info "no export entry"
                      {:type :validation
                       :code :empty-export})))
    (when (wwasm/cancelled? job-id)
      (throw (export-cancelled)))
    (let [bytes (wasm.exports/export-bytes export)]
      (when-not bytes
        (throw (ex-info "wasm export returned no bytes"
                        {:type :internal
                         :code :wasm-export-empty
                         :object-id (:object-id export)})))
      (on-progress 1 1)
      (p/resolved (wapi/create-blob bytes (export-mtype (:type export)))))))

(defn- build-zip
  [exports job-id on-progress]
  (let [writer (uz/writer (uz/blob-writer :mtype "application/zip"))
        used   (volatile! #{})
        entries
        (mapv (fn [export]
                (let [filename (wasm.exports/sanitize-filename export @used)]
                  (vswap! used conj filename)
                  [filename export]))
              exports)
        total  (count entries)]
    (->> (p/reduce
          (fn [writer [index [filename export]]]
            (when (wwasm/cancelled? job-id)
              (throw (export-cancelled)))
            (let [bytes (wasm.exports/export-bytes export)]
              (when-not bytes
                (throw (ex-info "wasm export returned no bytes"
                                {:type :internal
                                 :code :wasm-export-empty
                                 :object-id (:object-id export)})))
              (->> (uz/add writer filename bytes)
                   (p/fmap (fn [_]
                             (on-progress (inc index) total)
                             writer)))))
          writer
          (map-indexed vector entries))
         (p/mcat (fn [writer]
                   (uz/close writer)))
         (p/merr (fn [cause]
                   (try (uz/close writer) (catch :default _))
                   (p/rejected cause))))))

(defn- build-frames-pdf
  [exports job-id on-progress]
  (let [ids   (mapv :object-id exports)
        total (count ids)]
    (wasm.api/pdf-export-begin!)
    (->> (p/reduce
          (fn [_ [index id]]
            (when (wwasm/cancelled? job-id)
              (throw (export-cancelled)))
            (wasm.api/pdf-export-add-page! id)
            (on-progress (inc index) total)
            ;; Yield so a cancel that arrived during add-page can stop
            ;; before the next page (nil would run every page in one turn).
            (p/resolved nil))
          nil
          (map-indexed vector ids))
         (p/mcat
          (fn [_]
            (let [bytes (wasm.api/pdf-export-finish)]
              (when-not bytes
                (throw (ex-info "wasm pdf export returned no bytes"
                                {:type :internal
                                 :code :wasm-export-empty})))
              (p/resolved (wapi/create-blob bytes "application/pdf")))))
         (p/merr
          (fn [cause]
            (wasm.api/pdf-export-abort!)
            (p/rejected cause))))))

(defn- export-entry
  "Keeps only the fields the worker needs to render and name files."
  [{:keys [object-id type scale name suffix]}]
  {:object-id object-id
   :type type
   :scale (or scale 1)
   :name name
   :suffix suffix})

(defn- run-local-export
  [{:keys [job-id kind exports objects background] export-name :name}]
  (let [exports (mapv export-entry exports)]
    (->> (rx/from @wwasm/module)
         (rx/mapcat
          (fn [_]
            (rx/create
             (fn [subs]
               (let [done? (volatile! false)
                     ;; Never `rx/error!` here: the main `ask-many!` stream would
                     ;; hang because worker error replies used to drop `:error`
                     ;; and never send `completed`. Emit a terminal message instead.
                     finish!
                     (fn [result cause]
                       (when-not @done?
                         (vreset! done? true)
                         (try (wasm.api/clear-canvas) (catch :default _))
                         (wwasm/clear-cancel job-id)
                         (cond
                           (or (wwasm/cancelled? job-id)
                               (= :export-cancelled (:type (ex-data cause))))
                           (do
                             (rx/push! subs {:type :cancelled})
                             (rx/end! subs))

                           cause
                           (do
                             (rx/push! subs {:type :error
                                             :hint (ex-message cause)
                                             :data (ex-data cause)})
                             (rx/end! subs))

                           :else
                           (let [entry    (first exports)
                                 filename (case kind
                                            :pdf-frames
                                            (str (or export-name "export") ".pdf")

                                            :single
                                            (wasm.exports/sanitize-filename entry #{})

                                            (str (or export-name "export") ".zip"))
                                 mtype    (case kind
                                            :pdf-frames "application/pdf"
                                            :single     (export-mtype (:type entry))
                                            "application/zip")]
                             (rx/push! subs {:type :done
                                             :filename filename
                                             :mtype mtype
                                             :blob result})
                             (rx/end! subs)))))

                     on-progress
                     (fn [done total]
                       (rx/push! subs {:type :progress
                                       :done done
                                       :total total}))

                     start-export!
                     (fn []
                       ;; `:on-render` runs after process-pending (images +
                       ;; fonts). `:on-shapes-ready` fires earlier and would
                       ;; export empty image fills / missing typefaces.
                       (when (wwasm/cancelled? job-id)
                         (finish! nil (export-cancelled)))
                       (when-not @done?
                         (try
                           (let [work (case kind
                                        :pdf-frames
                                        (build-frames-pdf exports job-id on-progress)

                                        :single
                                        (build-single exports job-id on-progress)

                                        (build-zip exports job-id on-progress))]
                             (->> work
                                  (p/fnly (fn [blob cause]
                                            (finish! blob cause)))))
                           (catch :default cause
                             (finish! nil cause)))))

                     canvas (js/OffscreenCanvas. canvas-size canvas-size)
                     init?  (wasm.api/init-canvas-context canvas)]
                 (cond
                   (not init?)
                   (finish! nil (ex-info "failed to init worker wasm canvas"
                                         {:type :internal
                                          :code :wasm-canvas-init-failed}))

                   (not (wasm/live?))
                   (finish! nil (ex-info "worker wasm context is not live"
                                         {:type :internal
                                          :code :wasm-not-live}))

                   (empty? objects)
                   (finish! nil (ex-info "no shapes to export"
                                         {:type :validation
                                          :code :empty-export-objects}))

                   (wwasm/cancelled? job-id)
                   (finish! nil (export-cancelled))

                   :else
                   (try
                     (let [bgcolor (or background cc/canvas)
                           vbox    {:x 0 :y 0 :width canvas-size :height canvas-size}]
                       (reset! wasm/disable-request-render? true)
                       (wasm.api/initialize-viewport
                        objects 1 vbox
                        :background bgcolor
                        :force-sync true
                        :on-render start-export!))
                     (catch :default cause
                       (finish! nil cause))))
                 (fn []
                   (wwasm/cancel-job job-id)
                   (try (wasm.api/pdf-export-abort!) (catch :default _))
                   (try (wasm.api/clear-canvas) (catch :default _)))))))))))

(defmethod impl/handler :exports/run-local
  [message _]
  (let [job-id (or (:job-id message) (uuid/next))
        msg    (assoc message :job-id job-id)]
    (wwasm/submit #(run-local-export msg))))

(defmethod impl/handler :exports/cancel
  [{:keys [job-id]} _]
  (when job-id
    (wwasm/cancel-job job-id))
  nil)
