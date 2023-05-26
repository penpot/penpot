;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.texts
  (:require
   [app.common.attrs :as attrs]
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.math :as mth]
   [app.common.pages.helpers :as cph]
   [app.common.text :as txt]
   [app.common.types.modifiers :as ctm]
   [app.common.uuid :as uuid]
   [app.main.data.events :as ev]
   [app.main.data.workspace.changes :as dch]
   [app.main.data.workspace.common :as dwc]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.modifiers :as dwm]
   [app.main.data.workspace.selection :as dws]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.data.workspace.state-helpers :as wsh]
   [app.main.data.workspace.undo :as dwu]
   [app.main.fonts :as fonts]
   [app.util.router :as rt]
   [app.util.text-editor :as ted]
   [app.util.timers :as ts]
   [beicon.core :as rx]
   [cuerdas.core :as str]
   [potok.core :as ptk]))

;; -- Attrs

(def text-typography-attrs
  [:typography-ref-id
   :typography-ref-file])

(def text-fill-attrs
  [:fill-color
   :fill-opacity
   :fill-color-ref-id
   :fill-color-ref-file
   :fill-color-gradient])

(def text-font-attrs
  [:font-id
   :font-family
   :font-variant-id
   :font-size
   :font-weight
   :font-style])

(def text-align-attrs
  [:text-align])

(def text-direction-attrs
  [:text-direction])

(def text-spacing-attrs
  [:line-height
   :letter-spacing])

(def text-valign-attrs
  [:vertical-align])

(def text-decoration-attrs
  [:text-decoration])

(def text-transform-attrs
  [:text-transform])

(def shape-attrs
  [:grow-type])

(def root-attrs text-valign-attrs)

(def paragraph-attrs
  (d/concat-vec
   text-align-attrs
   text-direction-attrs))

(def text-attrs
  (d/concat-vec
   text-typography-attrs
   text-font-attrs
   text-spacing-attrs
   text-decoration-attrs
   text-transform-attrs))

(def attrs (d/concat-set shape-attrs root-attrs paragraph-attrs text-attrs))

;; -- Editor

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
        (let [objects      (wsh/lookup-page-objects state)
              shape        (get objects id)
              editor-state (get-in state [:workspace-editor-state id])
              content      (-> editor-state
                               (ted/get-editor-current-content))
              text         (-> (ted/get-editor-current-plain-text editor-state)
                               (txt/generate-shape-name))
              new-shape?   (nil? (:content shape))]
          (if (ted/content-has-text? content)
            (let [content (d/merge (ted/export-content content)
                                   (dissoc (:content shape) :children))
                  modifiers (get-in state [:workspace-text-modifier id])]
              (rx/merge
               (rx/of (update-editor-state shape nil))
               (when (and (not= content (:content shape))
                          (some? (:current-page-id state)))
                 (rx/of
                  (dch/update-shapes
                   [id]
                   (fn [shape]
                     (let [{:keys [width height]} modifiers]
                       (-> shape
                           (assoc :content content)
                           (cond-> new-shape?
                             (assoc :name text))
                           (cond-> (or (some? width) (some? height))
                             (gsh/transform-shape (ctm/change-size shape width height)))))))
                  (dwu/commit-undo-transaction (:id shape))))))

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
  (let [update-attrs-fn #(update-fn % attrs)
        transform   #(txt/transform-nodes pred-fn update-attrs-fn %)]
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
  (> (mth/abs (- old-dim new-dim)) 0.1))

(defn commit-resize-text
  []
  (ptk/reify ::commit-resize-text
    ptk/WatchEvent
    (watch [_ state _]
      (let [props (::resize-text-debounce-props state)
            objects (wsh/lookup-page-objects state)
            undo-id (js/Symbol)]

        (letfn [(changed-text? [id]
                  (let [shape (get objects id)
                        [new-width new-height] (get props id)]
                    (or (and (not-changed? (:width shape) new-width) (= (:grow-type shape) :auto-width))
                        (and (not-changed? (:height shape) new-height)
                             (or (= (:grow-type shape) :auto-height) (= (:grow-type shape) :auto-width))))))

                (update-fn [{:keys [id selrect grow-type] :as shape}]
                  (let [{shape-width :width shape-height :height} selrect
                        [new-width new-height] (get props id)

                        shape
                        (cond-> shape
                          (and (not-changed? shape-width new-width) (= grow-type :auto-width))
                          (gsh/transform-shape (ctm/change-dimensions-modifiers shape :width new-width {:ignore-lock? true})))

                        shape
                        (cond-> shape
                          (and (not-changed? shape-height new-height)
                               (or (= grow-type :auto-height) (= grow-type :auto-width)))
                          (gsh/transform-shape (ctm/change-dimensions-modifiers shape :height new-height {:ignore-lock? true})))]

                    shape))]

          (let [ids (->> (keys props) (filter changed-text?))]
            (rx/of (dwu/start-undo-transaction undo-id)
                   (dch/update-shapes ids update-fn {:reg-objects? true :stack-undo? true :ignore-remote? true})
                   (ptk/data-event :layout/update ids)
                   (dwu/commit-undo-transaction undo-id))))))))

(defn resize-text
  [id new-width new-height]

  (let [cur-event (js/Symbol)]
    (ptk/reify ::resize-text
      ptk/UpdateEvent
      (update [_ state]
        (-> state
            (update ::resize-text-debounce-props (fnil assoc {}) id [new-width new-height])
            (cond-> (nil? (::resize-text-debounce-event state))
              (assoc ::resize-text-debounce-event cur-event))))

      ptk/WatchEvent
      (watch [_ state stream]
        (if (= (::resize-text-debounce-event state) cur-event)
          (let [stopper (->> stream (rx/filter (ptk/type? :app.main.data.workspace/finalize)))]
            (rx/concat
             (rx/merge
              (->> stream
                   (rx/filter (ptk/type? ::resize-text))
                   (rx/debounce 50)
                   (rx/take 1)
                   (rx/map #(commit-resize-text))
                   (rx/take-until stopper))
              (rx/of (resize-text id new-width new-height)))
             (rx/of #(dissoc % ::resize-text-debounce-props ::resize-text-debounce-event))))
          (rx/empty))))))

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

  (let [new-shape
        (cond-> shape
          (some? width)
          (gsh/transform-shape (ctm/change-dimensions-modifiers shape :width width {:ignore-lock? true}))

          (some? height)
          (gsh/transform-shape (ctm/change-dimensions-modifiers shape :height height {:ignore-lock? true}))

          (some? position-data)
          (assoc :position-data position-data))

        delta-move
        (gpt/subtract (gpt/point (:selrect new-shape))
                      (gpt/point (:selrect shape)))

        new-shape
        (update new-shape :position-data gsh/move-position-data delta-move)]

    new-shape))

(defn commit-update-text-modifier
  []
  (ptk/reify ::commit-update-text-modifier
    ptk/WatchEvent
    (watch [_ state _]
      (let [ids (::update-text-modifier-debounce-ids state)]
        (let [modif-tree (dwm/create-modif-tree ids (ctm/reflow-modifiers))]
          (rx/of (dwm/update-modifiers modif-tree false true)))))))

(defn update-text-modifier
  [id props]

  (let [cur-event (js/Symbol)]
    (ptk/reify ::update-text-modifier
      ptk/UpdateEvent
      (update [_ state]
        (-> state
            (update-in [:workspace-text-modifier id] (fnil merge {}) props)
            (update ::update-text-modifier-debounce-ids (fnil conj #{}) id)
            (cond-> (nil? (::update-text-modifier-debounce-event state))
              (assoc ::update-text-modifier-debounce-event cur-event))))

      ptk/WatchEvent
      (watch [_ state stream]
        (if (= (::update-text-modifier-debounce-event state) cur-event)
          (let [stopper (->> stream (rx/filter (ptk/type? :app.main.data.workspace/finalize)))]
            (rx/concat
             (rx/merge
              (->> stream
                   (rx/filter (ptk/type? ::update-text-modifier))
                   (rx/debounce 50)
                   (rx/take 1)
                   (rx/map #(commit-update-text-modifier))
                   (rx/take-until stopper))
              (rx/of (update-text-modifier id props)))
             (rx/of #(dissoc % ::update-text-modifier-debounce-event ::update-text-modifier-debounce-ids))))
          (rx/empty))))))

(defn clean-text-modifier
  [id]
  (ptk/reify ::clean-text-modifier
    ptk/WatchEvent
    (watch [_ state _]
      (let [current-value (dm/get-in state [:workspace-text-modifier id])]
        ;; We only dissocc the value when hasn't change after a time
        (->> (rx/of (fn [state]
                      (cond-> state
                        (identical? (dm/get-in state [:workspace-text-modifier id]) current-value)
                        (update :workspace-text-modifier dissoc id))))
             (rx/delay 100))))))

(defn remove-text-modifier
  [id]
  (ptk/reify ::remove-text-modifier
    ptk/UpdateEvent
    (update [_ state]
      (d/dissoc-in state [:workspace-text-modifier id]))

    ptk/WatchEvent
    (watch [_ _ _]
      (rx/of (dwm/apply-modifiers {:stack-undo? true})))))

(defn commit-position-data
  []
  (ptk/reify ::commit-position-data
    ptk/UpdateEvent
    (update [_ state]
      (let [ids (keys (::update-position-data state))]
        (update state :workspace-text-modifier #(apply dissoc % ids))))

    ptk/WatchEvent
    (watch [_ state _]
      (let [position-data (::update-position-data state)]
        (rx/concat
         (rx/of (dch/update-shapes
                 (keys position-data)
                 (fn [shape]
                   (-> shape
                       (assoc :position-data (get position-data (:id shape)))))
                 {:stack-undo? true :reg-objects? false :ignore-remote? true}))
         (rx/of (fn [state]
                  (dissoc state ::update-position-data-debounce ::update-position-data))))))))

(defn update-position-data
  [id position-data]

  (let [cur-event (js/Symbol)]
    (ptk/reify ::update-position-data
      ptk/UpdateEvent
      (update [_ state]
        (let [state (assoc-in state [:workspace-text-modifier id :position-data] position-data)]
          (if (nil? (::update-position-data-debounce state))
            (assoc state ::update-position-data-debounce cur-event)
            (assoc-in state [::update-position-data id] position-data))))

      ptk/WatchEvent
      (watch [_ state stream]
        (if (= (::update-position-data-debounce state) cur-event)
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

(defn update-attrs
  [id attrs]
  (ptk/reify ::update-attrs
    ptk/WatchEvent
    (watch [_ _ _]
      (rx/concat
       (let [attrs (select-keys attrs root-attrs)]
         (if-not (empty? attrs)
           (rx/of (update-root-attrs {:id id :attrs attrs}))
           (rx/empty)))

       (let [attrs (select-keys attrs paragraph-attrs)]
         (if-not (empty? attrs)
           (rx/of (update-paragraph-attrs {:id id :attrs attrs}))
           (rx/empty)))

       (let [attrs (select-keys attrs text-attrs)]
         (if-not (empty? attrs)
           (rx/of (update-text-attrs {:id id :attrs attrs}))
           (rx/empty)))))))


(defn apply-typography
  "A higher level event that has the resposability of to apply the
  specified typography to the selected shapes."
  [typography file-id]
  (ptk/reify ::apply-typography
    ptk/WatchEvent
    (watch [_ state _]
      (let [editor-state (:workspace-editor-state state)
            selected     (wsh/lookup-selected state)
            attrs        (-> typography
                             (assoc :typography-ref-file file-id)
                             (assoc :typography-ref-id (:id typography))
                             (dissoc :id :name))]

        (->> (rx/from (seq selected))
             (rx/map (fn [id]
                       (let [editor (get editor-state id)]
                         (update-text-attrs {:id id :editor editor :attrs attrs})))))))))

(defn generate-typography-name
  [{:keys [font-id font-variant-id] :as typography}]
  (let [{:keys [name]} (fonts/get-font-data font-id)]
    (assoc typography :name (str name " " (str/title font-variant-id)))))

(defn add-typography
  "A higher level version of dwl/add-typography, and has mainly two
  responsabilities: add the typography to the library and apply it to
  the currently selected text shapes (being aware of the open text
  editors."
  [file-id]
  (ptk/reify ::add-typography
    ptk/WatchEvent
    (watch [_ state _]
      (let [selected   (wsh/lookup-selected state)
            objects    (wsh/lookup-page-objects state)

            xform      (comp (keep (d/getf objects))
                             (filter cph/text-shape?))
            shapes     (into [] xform selected)
            shape      (first shapes)

            values     (current-text-values
                        {:editor-state (dm/get-in state [:workspace-editor-state (:id shape)])
                         :shape shape
                         :attrs text-attrs})

            multiple? (or (> 1 (count shapes))
                          (d/seek (partial = :multiple)
                                  (vals values)))

            values    (-> (d/without-nils values)
                          (select-keys
                           (d/concat-vec text-font-attrs
                                         text-spacing-attrs
                                         text-transform-attrs)))

            typ-id    (uuid/next)
            typ       (-> (if multiple?
                            txt/default-typography
                            (merge txt/default-typography values))
                          (generate-typography-name)
                          (assoc :id typ-id))]

        (rx/concat
         (rx/of (dwl/add-typography typ)
                (ptk/event ::ev/event {::ev/name "add-asset-to-library"
                                       :asset-type "typography"}))

         (when (not multiple?)
           (rx/of (update-attrs (:id shape)
                                {:typography-ref-id typ-id
                                 :typography-ref-file file-id}))))))))

