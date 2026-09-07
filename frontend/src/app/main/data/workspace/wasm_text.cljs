;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.data.workspace.wasm-text
  "Helpers/events to resize wasm text shapes without depending on workspace.texts.

  This exists to avoid circular deps:
  workspace.texts -> workspace.libraries -> workspace.texts"
  (:require
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.types.modifiers :as ctm]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace :as-alias dw]
   [app.main.data.workspace.modifiers :as dwm]
   [app.main.data.workspace.reflow :as wrf]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.data.workspace.undo :as dwu]
   [app.render-wasm.api :as wasm.api]
   [app.render-wasm.api.fonts :as wasm.fonts]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(def debounce-resize-text-time 40)

(defn get-wasm-text-new-size
  "Computes the new {width, height} for a text shape from WASM text layout.
  For :fixed grow-type, updates WASM content and returns current dimensions (no resize)."
  ([shape]
   (get-wasm-text-new-size shape (:content shape)))

  ([{:keys [id selrect grow-type] :as shape} content]
   ;; Skip when the WASM context is not ready (e.g. switching renderer while a
   ;; text shape is being edited): there is no design state to query, and
   ;; returning nil makes callers skip the WASM resize/modifier path.
   (when (and id (wasm.api/initialized?))
     (wasm.api/use-shape id)
     ;; While the WASM text editor is actively editing it already holds the live
     ;; content and layout. Re-pushing the content here calls `_clear_shape_text`
     ;; + `_update_shape_text_layout`, which resets the editor and drops every
     ;; keystroke after the first, so we just measure the live layout instead.
     (when-not (and (wasm.api/text-editor-has-focus?)
                    (= id (wasm.api/text-editor-get-active-shape-id)))
       (wasm.api/set-shape-text-content id content)
       (wasm.api/set-shape-text-images id content))
     (let [dimension (when (not= :fixed grow-type)
                       (wasm.api/get-text-dimensions))]
       ;; nil dimension = shape not present in WASM state; skip the resize.
       (when (or (= :fixed grow-type) (some? dimension))
         {:width  (if (#{:fixed :auto-height} grow-type)
                    (:width selrect)
                    (:width dimension))
          :height (if (= :fixed grow-type)
                    (:height selrect)
                    (:height dimension))})))))

(defn resize-wasm-text-modifiers
  ([shape]
   (resize-wasm-text-modifiers shape (:content shape)))

  ([{:keys [id points selrect] :as shape} content]
   (when-let [new-size (get-wasm-text-new-size shape content)]
     (let [width-scale  (/ (:width new-size) (:width selrect))
           height-scale (/ (:height new-size) (:height selrect))
           resize-v     (gpt/point width-scale height-scale)
           origin       (first points)]
       {id
        {:modifiers
         (ctm/resize-modifiers
          resize-v
          origin
          (:transform shape (gmt/matrix))
          (:transform-inverse shape (gmt/matrix)))}}))))

(defn resize-wasm-text
  "Resize a single text shape (auto-width/auto-height) by id.
  No-op if the id is not a text shape or is :fixed."
  [id]
  (ptk/reify ::resize-wasm-text
    ptk/WatchEvent
    (watch [_ state _]
      (let [objects (dsh/lookup-page-objects state)
            shape   (get objects id)
            resize-stream
            (if (and (some? shape)
                     (cfh/text-shape? shape)
                     (not= :fixed (:grow-type shape)))
              (rx/of (dwm/apply-wasm-modifiers (resize-wasm-text-modifiers shape)))
              (rx/empty))]
        (wrf/with-pending :text-resize [id] resize-stream)))))

(defn- merge-resize-debounce-opts
  [prev {:keys [undo-group undo-id skip-component-sync?]}]
  (cond-> (or prev {})
    (some? undo-group) (assoc :undo-group undo-group)
    (some? undo-id) (assoc :undo-id undo-id)
    skip-component-sync? (assoc :skip-component-sync? true)))

(defn resize-wasm-text-debounce-commit
  []
  (ptk/reify ::resize-wasm-text-debounce-commit
    ptk/WatchEvent
    (watch [_ state _]
      (let [ids (get state ::resize-wasm-text-debounce-ids)
            {:keys [undo-group undo-id skip-component-sync?]} (get state ::resize-wasm-text-debounce-opts)
            objects (dsh/lookup-page-objects state)

            modifiers
            (reduce
             (fn [modifiers id]
               (let [shape (get objects id)]
                 (cond-> modifiers
                   (and (some? shape)
                        (cfh/text-shape? shape)
                        (not= :fixed (:grow-type shape)))
                   (merge (resize-wasm-text-modifiers shape)))))
             {}
             ids)

            ;; When undo-id is present, extend the current undo transaction instead of
            ;; creating a new one, and commit it after the resize (single undo action).
            extend-tx? (some? undo-id)
            apply-opts (cond-> {}
                         (some? undo-group) (assoc :undo-group undo-group)
                         extend-tx? (assoc :undo-transation? false)
                         skip-component-sync? (assoc :skip-component-sync? true))]
        (cond
          (not (empty? modifiers))
          (if extend-tx?
            (rx/concat
             (rx/of (dwm/apply-wasm-modifiers modifiers apply-opts))
             (rx/of (dwu/commit-undo-transaction undo-id)))
            (rx/of (dwm/apply-wasm-modifiers modifiers apply-opts)))

          extend-tx?
          ;; No resize needed (e.g. :fixed grow-type) but we must commit the add
          (rx/of (dwu/commit-undo-transaction undo-id))

          :else
          (rx/empty))))))

;; This event will debounce the resize events so, if there are many, they
;; are processed at the same time and not one-by-one. This will improve
;; performance because it's better to make only one layout calculation instead
;; of (potentialy) hundreds.
(defn resize-wasm-text-debounce-inner
  ([id]
   (resize-wasm-text-debounce-inner id nil))
  ([id opts]
   (let [cur-event   (js/Symbol)
         reflow-task (wrf/task :text-resize [id])]
     (ptk/reify ::resize-wasm-text-debounce-inner
       ptk/UpdateEvent
       (update [_ state]
         (-> state
             (update ::resize-wasm-text-debounce-ids (fnil conj []) id)
             (update ::resize-wasm-text-reflow-tasks (fnil conj []) reflow-task)
             (cond-> (seq opts)
               (update ::resize-wasm-text-debounce-opts merge-resize-debounce-opts opts))
             (cond-> (nil? (::resize-wasm-text-debounce-event state))
               (assoc ::resize-wasm-text-debounce-event cur-event))))

       ptk/WatchEvent
       (watch [_ state stream]
         (wrf/start! reflow-task)
         (if (= (::resize-wasm-text-debounce-event state) cur-event)
           (let [stopper (->> stream (rx/filter (ptk/type? ::dw/finalize-workspace)))]
             (rx/concat
              (rx/merge
               (->> stream
                    (rx/filter (ptk/type? ::resize-wasm-text-debounce-inner))
                    (rx/debounce debounce-resize-text-time)
                    (rx/take 1)
                    (rx/map (fn [_] (resize-wasm-text-debounce-commit)))
                    (rx/take-until stopper))
               (rx/of (resize-wasm-text-debounce-inner id opts)))
              ;; Cleanup, reached both after the commit and when the stopper
              ;; cancels the debounce, so the batch always drains and stays
              ;; pending until the resize is applied. All exact tasks in the
              ;; batch are retained in state and finished by the cleanup.
              (rx/of (fn [state]
                       (wrf/finish-tasks! (::resize-wasm-text-reflow-tasks state))
                       (dissoc state
                               ::resize-wasm-text-debounce-ids
                               ::resize-wasm-text-reflow-tasks
                               ::resize-wasm-text-debounce-opts
                               ::resize-wasm-text-debounce-event)))))
           (rx/empty)))))))

(defn resize-wasm-text-debounce
  ([id]
   (resize-wasm-text-debounce id nil))
  ([id {:keys [undo-group undo-id skip-component-sync?] :as opts}]
   (ptk/reify ::resize-wasm-text-debounce
     ptk/WatchEvent
     (watch [_ state _]
       (let [page-id (:current-page-id state)
             objects (dsh/lookup-page-objects state page-id)
             content (dm/get-in objects [id :content])
             fonts   (wasm.fonts/get-content-fonts content)

             fonts-ready?
             (->> fonts
                  (every?
                   (fn [font]
                     (let [font-data (wasm.fonts/make-font-data font)]
                       (wasm.fonts/font-ready? font-data)))))

             resize-wasm-stream
             (if fonts-ready?
               (let [pass-opts (when (or (some? undo-group) (some? undo-id) skip-component-sync?)
                                 (cond-> {}
                                   (some? undo-group) (assoc :undo-group undo-group)
                                   (some? undo-id) (assoc :undo-id undo-id)
                                   skip-component-sync? (assoc :skip-component-sync? true)))]
                 (rx/of (resize-wasm-text-debounce-inner id pass-opts)))

               ;; Fonts not loaded; retry after 20 msecs
               (->> (rx/of (resize-wasm-text-debounce id opts))
                    (rx/delay 20)))]

         ;; Holds the shape pending across the font-retry loop: the retried
         ;; event opens its task before this one drains, so the task set never
         ;; becomes empty in between.
         (wrf/with-pending :text-resize [id] resize-wasm-stream))))))

(defn resize-wasm-text-all
  "Resize all text shapes (auto-width/auto-height) from a collection of ids."
  ([ids]
   (resize-wasm-text-all ids nil))
  ([ids opts]
   (ptk/reify ::resize-wasm-text-all
     ptk/WatchEvent
     (watch [_ state stream]
       (let [resize-stream
             (->> (rx/from ids)
                  (rx/map #(resize-wasm-text-debounce % opts)))

             buffer-finished-stream
             (->> (rx/merge
                   (->> stream
                        (rx/filter (ptk/type? ::dwsh/update-shapes-buffer-commit))
                        (rx/map (constantly :commit)))
                   ;; Let a buffered commit beat the stop signal.
                   (->> stream
                        (rx/filter (ptk/type? ::dwsh/update-shapes-buffer-stop))
                        (rx/observe-on :async)
                        (rx/map (constantly :stop)))
                   (->> stream
                        (rx/filter (ptk/type? ::dw/finalize-workspace))
                        (rx/map (constantly :finalize))))
                  (rx/take 1))]
         (if (::dwsh/update-shapes-buffer state)
           ;; If we're in the middle of a token propagation we wait until is finished to
           ;; recalculate the text sizes. The shapes stay pending for that whole wait,
           ;; since the per-shape debounce only marks them once dispatched.
           (wrf/with-pending
             :text-resize ids
             (->> buffer-finished-stream
                  (rx/mapcat
                   (fn [reason]
                     (if (= reason :finalize)
                       (rx/empty)
                       resize-stream)))))
           resize-stream))))))
