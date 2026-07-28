;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

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
   [app.main.data.workspace.modifiers :as dwm]
   [app.main.data.workspace.reflow :as wrf]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.data.workspace.undo :as dwu]
   [app.render-wasm.api :as wasm.api]
   [app.render-wasm.api.fonts :as wasm.fonts]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(def debounce-resize-text-time 40)

;; Attributes whose change invalidates the WASM-measured selrect of a text
;; shape, i.e. its geometry must be re-derived from the WASM text layout.
(def ^:private text-remeasure-attr? #{:content :grow-type})

(defn commit-text-remeasure-ids
  "Ids of shapes in a commit's `redo-changes` whose selrect went stale and must
  be re-measured from the WASM text layout: a `:mod-obj` changed a
  layout-relevant attribute (see `text-remeasure-attr?`) without also carrying
  the resulting geometry.

  The `:selrect` guard skips commits that already set the geometry themselves
  (text-editor finalize, interactive resize, the re-measure commit itself), so
  those neither trigger a redundant resize nor create a feedback loop.

  Type filtering (text, non-`:fixed`) is left to `resize-wasm-text-all`."
  [redo-changes]
  (into #{}
        (comp
         (filter #(= :mod-obj (:type %)))
         (keep (fn [{:keys [id operations]}]
                 (let [attrs (into #{}
                                   (comp (filter #(= :set (:type %))) (map :attr))
                                   operations)]
                   (when (and (some text-remeasure-attr? attrs)
                              (not (contains? attrs :selrect)))
                     id)))))
        redo-changes))

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

(defn resize-wasm-text-debounce-commit
  ([]
   (resize-wasm-text-debounce-commit nil))
  ([undo-id]
   (ptk/reify ::resize-wasm-text-debounce-commit
     ptk/WatchEvent
     (watch [_ state _]
       (let [ids (get state ::resize-wasm-text-debounce-ids)
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

             ;; The re-measure only syncs the shape's selrect with the WASM text
             ;; layout; it is derived geometry, not a user action, so it must
             ;; never land on the undo stack (an undo of it alone would
             ;; re-expose the stale selrect). Undo/redo of the change that
             ;; triggered it re-derives the geometry through the commit-level
             ;; invalidation in `initialize-workspace`.
             extend-tx? (some? undo-id)
             apply-opts {:save-undo? false :undo-transation? false}]
         (cond
           (not (empty? modifiers))
           (if extend-tx?
             (rx/concat
              (rx/of (dwm/apply-wasm-modifiers modifiers apply-opts))
              (rx/of (dwu/commit-undo-transaction undo-id)))
             (rx/of (dwm/apply-wasm-modifiers modifiers apply-opts)))

           extend-tx?
           ;; No resize needed (e.g. :fixed grow-type) but we must still close
           ;; the transaction opened by the caller (e.g. for the added shape).
           (rx/of (dwu/commit-undo-transaction undo-id))

           :else
           (rx/empty)))))))

;; This event will debounce the resize events so, if there are many, they
;; are processed at the same time and not one-by-one. This will improve
;; performance because it's better to make only one layout calculation instead
;; of (potentialy) hundreds.
(defn resize-wasm-text-debounce-inner
  ([id]
   (resize-wasm-text-debounce-inner id nil))
  ([id {:keys [undo-id]}]
   (let [cur-event   (js/Symbol)
         reflow-task (wrf/task :text-resize [id])]
     (ptk/reify ::resize-wasm-text-debounce-inner
       ptk/UpdateEvent
       (update [_ state]
         (-> state
             (update ::resize-wasm-text-debounce-ids (fnil conj []) id)
             (update ::resize-wasm-text-reflow-tasks (fnil conj []) reflow-task)
             (cond-> (nil? (::resize-wasm-text-debounce-event state))
               (assoc ::resize-wasm-text-debounce-event cur-event))))

       ptk/WatchEvent
       (watch [_ state stream]
         (wrf/start! reflow-task)
         (if (= (::resize-wasm-text-debounce-event state) cur-event)
           (let [stopper (->> stream (rx/filter (ptk/type? :app.main.data.workspace/finalize)))]
             (rx/concat
              (rx/merge
               (->> stream
                    (rx/filter (ptk/type? ::resize-wasm-text-debounce-inner))
                    (rx/debounce debounce-resize-text-time)
                    (rx/take 1)
                    (rx/map (fn [evt]
                              (resize-wasm-text-debounce-commit
                               (some-> evt meta :undo-id))))
                    (rx/take-until stopper))
               (rx/of (with-meta
                        (resize-wasm-text-debounce-inner id)
                        {:undo-id undo-id})))
              ;; Cleanup, reached both after the commit and when the stopper
              ;; cancels the debounce, so the batch always drains and stays
              ;; pending until the resize is applied. All exact tasks in the
              ;; batch are retained in state and finished by the cleanup.
              (rx/of (fn [state]
                       (run! wrf/finish! (::resize-wasm-text-reflow-tasks state))
                       (dissoc state
                               ::resize-wasm-text-debounce-ids
                               ::resize-wasm-text-reflow-tasks
                               ::resize-wasm-text-debounce-event)))))
           (rx/empty)))))))

(defn resize-wasm-text-debounce
  ([id]
   (resize-wasm-text-debounce id nil))
  ([id {:keys [undo-id] :as opts}]
   (ptk/reify ::resize-wasm-text-debounce
     ptk/WatchEvent
     (watch [_ state _]
       (let [page-id (:current-page-id state)
             objects (dsh/lookup-page-objects state page-id)
             content (dm/get-in objects [id :content])
             fonts   (wasm.fonts/get-content-fonts content)

             fonts-loaded?
             (->> fonts
                  (every?
                   (fn [font]
                     (let [font-data (wasm.fonts/make-font-data font)]
                       (wasm.fonts/font-stored? font-data (:emoji? font-data))))))

             resize-wasm-stream
             (if fonts-loaded?
               (let [pass-opts (when (some? undo-id)
                                 {:undo-id undo-id})]
                 (rx/of (resize-wasm-text-debounce-inner id pass-opts)))

               ;; Fonts not loaded; retry after 20 msecs
               (->> (rx/of (resize-wasm-text-debounce id opts))
                    (rx/delay 20)))]

         ;; Holds the shape pending across the font-retry loop: the retried
         ;; event opens its task before this one drains, so the task set never
         ;; becomes empty in between.
         (wrf/with-pending :text-resize [id] resize-wasm-stream))))))

(defn resize-wasm-text-all
  "Resize all text shapes (auto-width/auto-height) from a collection of ids.

  The shape currently being edited is skipped: the text editor already renders
  and measures the growing text live and resizes it on finalize, so an
  automatic per-keystroke resize here would be redundant and reintroduce typing
  lag (see the guard in `app.main.data.workspace.texts`)."
  [ids]
  (ptk/reify ::resize-wasm-text-all
    ptk/WatchEvent
    (watch [_ state stream]
      (let [editing (get-in state [:workspace-local :edition])
            ids     (remove #(= % editing) ids)
            resize-stream
            (->> (rx/from ids)
                 (rx/map resize-wasm-text-debounce))]
        (if (::dwsh/update-shapes-buffer state)
          ;; If we're in the middle of a token propagation we wait until is finished to
          ;; recalculate the text sizes. The shapes stay pending for that whole wait,
          ;; since the per-shape debounce only marks them once dispatched.
          (wrf/with-pending
            :text-resize ids
            (->> stream
                 (rx/filter (ptk/type? ::dwsh/update-shapes-buffer-commit))
                 (rx/take 1)
                 (rx/mapcat (constantly resize-stream))))
          resize-stream)))))
