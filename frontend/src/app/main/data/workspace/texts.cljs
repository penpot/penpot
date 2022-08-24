;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.data.workspace.texts
  (:require
   [app.common.attrs :as attrs]
   [app.common.data :as d]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.math :as mth]
   [app.common.pages.helpers :as cph]
   [app.common.text :as txt]
   [app.common.uuid :as uuid]
   [app.main.data.workspace.changes :as dch]
   [app.main.data.workspace.common :as dwc]
   [app.main.data.workspace.selection :as dws]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.data.workspace.state-helpers :as wsh]
   [app.main.data.workspace.undo :as dwu]
   [app.util.router :as rt]
   [app.util.text-editor :as ted]
   [app.util.timers :as ts]
   [beicon.core :as rx]
   [potok.core :as ptk]))

(defn update-editor
  [editor]
  (ptk/reify ::update-editor
    ptk/UpdateEvent
    (update [_ state]
      (if (some? editor)
        (assoc state :workspace-editor editor)
        (dissoc state :workspace-editor)))))

(defn focus-editor
  []
  (ptk/reify ::focus-editor
    ptk/EffectEvent
    (effect [_ state _]
      (when-let [editor (:workspace-editor state)]
        (ts/schedule #(.focus ^js editor))))))

(defn update-editor-state
  [{:keys [id] :as shape} editor-state]
  (ptk/reify ::update-editor-state
    ptk/UpdateEvent
    (update [_ state]
      (if (some? editor-state)
        (update state :workspace-editor-state assoc id editor-state)
        (update state :workspace-editor-state dissoc id)))))

(defn finalize-editor-state
  [id]
  (ptk/reify ::finalize-editor-state
    ptk/WatchEvent
    (watch [_ state _]
      (when (dwc/initialized? state)
        (let [objects (wsh/lookup-page-objects state)
              shape   (get objects id)
              content (-> (get-in state [:workspace-editor-state id])
                          (ted/get-editor-current-content))]
          (if (ted/content-has-text? content)
            (let [content (d/merge (ted/export-content content)
                                   (dissoc (:content shape) :children))
                  modifiers (get-in state [:workspace-text-modifier id])]
              (rx/merge
               (rx/of (update-editor-state shape nil))
               (when (and (not= content (:content shape))
                          (some? (:current-page-id state)))
                 (rx/of
                  (dch/update-shapes [id] (fn [shape]
                                            (-> shape
                                                (assoc :content content)
                                                (merge modifiers))))
                  (dwu/commit-undo-transaction)))))

            (when (some? id)
              (rx/of (dws/deselect-shape id)
                     (dwsh/delete-shapes #{id})))))))))

(defn initialize-editor-state
  [{:keys [id content] :as shape} decorator]
  (ptk/reify ::initialize-editor-state
    ptk/UpdateEvent
    (update [_ state]
      (let [text-state (some->> content ted/import-content)
            attrs (d/merge txt/default-text-attrs
                           (get-in state [:workspace-global :default-font]))
            editor (cond-> (ted/create-editor-state text-state decorator)
                     (and (nil? content) (some? attrs))
                     (ted/update-editor-current-block-data attrs))]
        (-> state
            (assoc-in [:workspace-editor-state id] editor))))

    ptk/WatchEvent
    (watch [_ _ stream]
      ;; We need to finalize editor on two main events: (1) when user
      ;; explicitly navigates to other section or page; (2) when user
      ;; leaves the editor.
      (->> (rx/merge
            (rx/filter (ptk/type? ::rt/navigate) stream)
            (rx/filter #(= ::finalize-editor-state %) stream))
           (rx/take 1)
           (rx/map #(finalize-editor-state id))))))

(defn select-all
  "Select all content of the current editor. When not editor found this
  event is noop."
  [{:keys [id] :as shape}]
  (ptk/reify ::editor-select-all
    ptk/UpdateEvent
    (update [_ state]
      (d/update-in-when state [:workspace-editor-state id] ted/editor-select-all))))

(defn cursor-to-end
  [{:keys [id] :as shape}]
  (ptk/reify ::cursor-to-end
    ptk/UpdateEvent
    (update [_ state]
      (d/update-in-when state [:workspace-editor-state id] ted/cursor-to-end))))

;; --- Helpers

(defn to-new-fills
  [data]
  [(d/without-nils (select-keys data [:fill-color :fill-opacity :fill-color-gradient :fill-color-ref-id :fill-color-ref-file]))])

(defn- shape-current-values
  [shape pred attrs]
  (let [root  (:content shape)
        nodes (->> (txt/node-seq pred root)
                   (map (fn [node]
                          (if (txt/is-text-node? node)
                            (let [fills
                                  (cond
                                    (or (some? (:fill-color node))
                                        (some? (:fill-opacity node))
                                        (some? (:fill-color-gradient node)))
                                    (to-new-fills node)

                                    (some? (:fills node))
                                    (:fills node)

                                    :else
                                    (:fills txt/default-text-attrs))]
                              (-> (merge txt/default-text-attrs node)
                                  (assoc :fills fills)))
                            node))))]
    (attrs/get-attrs-multi nodes attrs)))

(defn current-root-values
  [{:keys [attrs shape]}]
  (shape-current-values shape txt/is-root-node? attrs))

(defn current-paragraph-values
  [{:keys [editor-state attrs shape]}]
  (if editor-state
    (-> (ted/get-editor-current-block-data editor-state)
        (select-keys attrs))
    (shape-current-values shape txt/is-paragraph-node? attrs)))

(defn current-text-values
  [{:keys [editor-state attrs shape]}]
  (if editor-state
    (let [result (-> (ted/get-editor-current-inline-styles editor-state)
                     (select-keys attrs))
          result (if (empty? result) txt/default-text-attrs result)]
      result)
    (shape-current-values shape txt/is-text-node? attrs)))


;; --- TEXT EDITION IMPL

(defn- update-text-content
  [shape pred-fn update-fn attrs]
  (let [update-attrs #(update-fn % attrs)
        transform   #(txt/transform-nodes pred-fn update-attrs %)]
    (-> shape
        (update :content transform))))

(defn update-root-attrs
  [{:keys [id attrs]}]
  (ptk/reify ::update-root-attrs
    ptk/WatchEvent
    (watch [_ state _]
      (let [objects   (wsh/lookup-page-objects state)
            shape     (get objects id)

            update-fn
            (fn [shape]
              (if (some? (:content shape))
                (update-text-content shape txt/is-root-node? d/txt-merge attrs)
                (assoc shape :content (d/txt-merge {:type "root"} attrs))))

            shape-ids (cond (cph/text-shape? shape)  [id]
                            (cph/group-shape? shape) (cph/get-children-ids objects id))]

        (rx/of (dch/update-shapes shape-ids update-fn))))))

(defn update-paragraph-attrs
  [{:keys [id attrs]}]
  (let [attrs (d/without-nils attrs)]
    (ptk/reify ::update-paragraph-attrs
      ptk/UpdateEvent
      (update [_ state]
        (d/update-in-when state [:workspace-editor-state id] ted/update-editor-current-block-data attrs))

      ptk/WatchEvent
      (watch [_ state _]
        (when-not (some? (get-in state [:workspace-editor-state id]))
          (let [objects   (wsh/lookup-page-objects state)
                shape     (get objects id)

                merge-fn  (fn [node attrs]
                            (reduce-kv
                             (fn [node k v] (assoc node k v))
                             node
                             attrs))

                update-fn #(update-text-content % txt/is-paragraph-node? merge-fn attrs)
                shape-ids (cond
                            (cph/text-shape? shape)  [id]
                            (cph/group-shape? shape) (cph/get-children-ids objects id))]

            (rx/of (dch/update-shapes shape-ids update-fn))))))))

(defn update-text-attrs
  [{:keys [id attrs]}]
  (ptk/reify ::update-text-attrs
    ptk/UpdateEvent
    (update [_ state]
      (d/update-in-when state [:workspace-editor-state id] ted/update-editor-current-inline-styles attrs))

    ptk/WatchEvent
    (watch [_ state _]
      (when-not (some? (get-in state [:workspace-editor-state id]))
        (let [objects   (wsh/lookup-page-objects state)
              shape     (get objects id)
              update-node? (fn [node]
                             (or (txt/is-text-node? node)
                                 (txt/is-paragraph-node? node)))
              shape-ids (cond
                          (cph/text-shape? shape)  [id]
                          (cph/group-shape? shape) (cph/get-children-ids objects id))]
          (rx/of (dch/update-shapes shape-ids #(update-text-content % update-node? d/txt-merge attrs))))))))

(defn migrate-node
  [node]
  (let [color-attrs (select-keys node [:fill-color :fill-opacity :fill-color-ref-id :fill-color-ref-file :fill-color-gradient])]
    (cond-> node
      (nil? (:fills node))
      (assoc :fills (:fills txt/default-text-attrs))

      (and (d/not-empty? color-attrs) (nil? (:fills node)))
      (-> (dissoc :fill-color :fill-opacity :fill-color-ref-id :fill-color-ref-file :fill-color-gradient)
          (assoc :fills [color-attrs])))
    ))

(defn migrate-content
  [content]
  (txt/transform-nodes (some-fn txt/is-text-node? txt/is-paragraph-node?) migrate-node content))

(defn update-text-with-function
  [id update-node-fn]
  (ptk/reify ::update-text-with-function
    ptk/UpdateEvent
    (update [_ state]
      (d/update-in-when state [:workspace-editor-state id] ted/update-editor-current-inline-styles-fn (comp update-node-fn migrate-node)))

    ptk/WatchEvent
    (watch [_ state _]
      (when (nil? (get-in state [:workspace-editor-state id]))
        (let [objects   (wsh/lookup-page-objects state)
              shape     (get objects id)

              update-node? (some-fn txt/is-text-node? txt/is-paragraph-node?)

              shape-ids
              (cond
                (cph/text-shape? shape)  [id]
                (cph/group-shape? shape) (cph/get-children-ids objects id))

              update-content
              (fn [content]
                (->> content
                     (migrate-content)
                     (txt/transform-nodes update-node? update-node-fn)))

              update-shape
              (fn [shape]
                (d/update-when shape :content update-content))]

          (rx/of (dch/update-shapes shape-ids update-shape)))))))

;; --- RESIZE UTILS

(def start-edit-if-selected
  (ptk/reify ::start-edit-if-selected
    ptk/UpdateEvent
    (update [_ state]
      (let [objects  (wsh/lookup-page-objects state)
            selected (->> state wsh/lookup-selected (mapv #(get objects %)))]
        (cond-> state
          (and (= 1 (count selected))
               (= (-> selected first :type) :text))
          (assoc-in [:workspace-local :edition] (-> selected first :id)))))))

(defn not-changed? [old-dim new-dim]
  (> (mth/abs (- old-dim new-dim)) 1))

(defn resize-text
  [id new-width new-height]
  (ptk/reify ::resize-text
    ptk/WatchEvent
    (watch [_ _ _]
      (letfn [(update-fn [shape]
                (let [{:keys [selrect grow-type]} shape
                      {shape-width :width shape-height :height} selrect
                      modifier-width (gsh/resize-modifiers shape :width new-width)
                      modifier-height (gsh/resize-modifiers shape :height new-height)]
                  (cond-> shape
                    (and (not-changed? shape-width new-width) (= grow-type :auto-width))
                    (-> (assoc :modifiers modifier-width)
                        (gsh/transform-shape))

                    (and (not-changed? shape-height new-height)
                         (or (= grow-type :auto-height) (= grow-type :auto-width)))
                    (-> (assoc :modifiers modifier-height)
                        (gsh/transform-shape)))))]

        (rx/of (dch/update-shapes [id] update-fn {:reg-objects? true :save-undo? false}))))))

(defn save-font
  [data]
  (ptk/reify ::save-font
    ptk/UpdateEvent
    (update [_ state]
      (let [multiple? (->> data vals (d/seek #(= % :multiple)))]
        (cond-> state
          (not multiple?)
          (assoc-in [:workspace-global :default-font] data))))))

(defn apply-text-modifier
  [shape {:keys [width height position-data]}]

  (let [modifier-width (when width (gsh/resize-modifiers shape :width width))
        modifier-height (when height (gsh/resize-modifiers shape :height height))

        new-shape
        (cond-> shape
          (some? modifier-width)
          (-> (assoc :modifiers modifier-width)
              (gsh/transform-shape))

          (some? modifier-height)
          (-> (assoc :modifiers modifier-height)
              (gsh/transform-shape))

          (some? position-data)
          (assoc :position-data position-data))

        delta-move
        (gpt/subtract (gpt/point (:selrect new-shape))
                      (gpt/point (:selrect shape)))


        new-shape
        (update new-shape :position-data gsh/move-position-data (:x delta-move) (:y delta-move))]


    new-shape))

(defn update-text-modifier
  [id props]
  (ptk/reify ::update-text-modifier
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:workspace-text-modifier id] (fnil merge {}) props))))

(defn clean-text-modifier
  [id]
  (ptk/reify ::clean-text-modifier
    ptk/WatchEvent
    (watch [_ _ _]
      (->> (rx/of #(update % :workspace-text-modifier dissoc id))
           ;; We delay a bit the change so there is no weird transition to the user
           (rx/delay 50)))))

(defn remove-text-modifier
  [id]
  (ptk/reify ::remove-text-modifier
    ptk/UpdateEvent
    (update [_ state]
      (d/dissoc-in state [:workspace-text-modifier id]))))

(defn commit-position-data
  []
  (ptk/reify ::commit-position-data
    ptk/UpdateEvent
    (update [_ state]
      (let [ids (keys (::update-position-data state))]
        (update state :workspace-text-modifiers #(apply dissoc % ids))))

    ptk/WatchEvent
    (watch [_ state _]
      (let [position-data (::update-position-data state)]
        (rx/concat
         (rx/of (dch/update-shapes
                 (keys position-data)
                 (fn [shape]
                   (-> shape
                       (assoc :position-data (get position-data (:id shape)))))
                 {:save-undo? false :reg-objects? false}))
         (rx/of (fn [state]
                  (dissoc state ::update-position-data-debounce ::update-position-data))))))))

(defn update-position-data
  [id position-data]

  (let [start (uuid/next)]
    (ptk/reify ::update-position-data
      ptk/UpdateEvent
      (update [_ state]
        (let [state (assoc-in state [:workspace-text-modifier id :position-data] position-data)]
          (if (nil? (::update-position-data-debounce state))
            (assoc state ::update-position-data-debounce start)
            (assoc-in state [::update-position-data id] position-data))))

      ptk/WatchEvent
      (watch [_ state stream]
        (if (= (::update-position-data-debounce state) start)
          (let [stopper (->> stream (rx/filter (ptk/type? :app.main.data.workspace/finalize)))]
            (rx/merge
             (->> stream
                  (rx/filter (ptk/type? ::update-position-data))
                  (rx/debounce 50)
                  (rx/take 1)
                  (rx/map #(commit-position-data))
                  (rx/take-until stopper))
             (rx/of (update-position-data id position-data))))
          (rx/empty))))))
