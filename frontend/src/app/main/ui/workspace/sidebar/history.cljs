;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.workspace.sidebar.history
  (:require
   [app.common.data :as d]
   [app.main.data.workspace.common :as dwc]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.i18n :refer [t] :as i18n]
   [cuerdas.core :as str]
   [okulary.core :as l]
   [rumext.alpha :as mf]))

(def workspace-undo
  (l/derived :workspace-undo st/state))

(defn get-object
  "Searches for a shape inside the objects list or inside the undo history"
  [id entries objects]
  (let [search-deleted-shape
        (fn [id entries]
          (let [search-obj (fn [obj] (and (= (:type obj) :add-obj)
                                          (= (:id obj) id)))
                search-delete-entry (fn [{:keys [undo-changes redo-changes]}]
                                      (or (d/seek search-obj undo-changes)
                                          (d/seek search-obj redo-changes)))
                {:keys [obj]} (->> entries (d/seek search-delete-entry) search-delete-entry)]
            obj))]
    (or (get objects id)
        (search-deleted-shape id entries))))


(defn extract-operation
  "Generalizes the type of operation for different types of change"
  [change]
  (case (:type change)
    (:add-obj :add-page :add-color :add-media :add-component :add-typography) :new
    (:mod-obj :mod-page :mod-color :mod-media :mod-component :mod-typography) :modify
    (:del-obj :del-page :del-color :del-media :del-component :del-typography) :delete
    :mov-objects :move
    nil))

(defn parse-change
  "Given a single change parses the information into an uniform map"
  [change]
  (let [r (fn [type id]
            {:type type
             :operation (extract-operation change)
             :detail (:operations change)
             :id (cond
                   (and (coll? id) (= 1 (count id))) (first id)
                   (coll? id) :multiple
                   :else id)})]
    (case (:type change)
      :set-option (r :page (:page-id change))
      (:add-obj
       :mod-obj
       :del-obj) (r :shape (:id change))
      :reg-objects nil
      :mov-objects (r :shape (:shapes change))
      (:add-page
       :mod-page :del-page
       :mov-page) (r :page (:id change))
      (:add-color
       :mod-color) (r :color (get-in change [:color :id]))
      :del-color (r :color (:id change))
      :add-recent-color nil
      (:add-media
       :mod-media) (r :media (get-in change [:object :id]))
      :del-media (r :media (:id change))
      (:add-component
       :mod-component
       :del-component) (r :component (:id change))
      (:add-typography
       :mod-typography) (r :typography (get-in change [:typography :id]))
      :del-typography (r :typography (:id change))
      nil)))

(defn resolve-shape-types
  "Retrieve the type to be shown to the user"
  [entries objects]
  (let [resolve-type (fn [{:keys [type id]}]
                       (if (or (not= type :shape) (= id :multiple))
                         type
                         (:type (get-object id entries objects))))

        map-fn (fn [entry]
                 (if (and (= (:type entry) :shape)
                          (not= (:id entry) :multiple))
                   (assoc entry :type (resolve-type entry))
                   entry))]
    (fn [entries]
      (map map-fn entries))))

(defn entry-type->message
  "Formats the message that will be displayed to the user"
  [locale type multiple?]
  (let [arity (if multiple? "multiple" "single")
        attribute (name (or type :multiple))]
    (t locale (str/format "workspace.undo.entry.%s.%s" arity attribute))))

(defn entry->message [locale entry]
  (let [value (entry-type->message locale (:type entry) (= :multiple (:id entry)))]
    (case (:operation entry)
      :new (t locale "workspace.undo.entry.new" value)
      :modify (t locale "workspace.undo.entry.modify" value)
      :delete (t locale "workspace.undo.entry.delete" value)
      :move (t locale "workspace.undo.entry.move" value)
      (t locale "workspace.undo.entry.unknown" value))))

(defn entry->icon [{:keys [type]}]
  (case type
    :page i/file-html
    :shape i/layers
    :rect i/box
    :circle i/circle
    :text i/text
    :path i/curve
    :frame i/artboard
    :group i/folder
    :color i/palette
    :typography i/titlecase
    :component i/component
    :media i/image
    :image i/image
    i/layers))

(defn is-shape? [type]
  (contains? #{:shape :rect :circle :text :path :frame :group} type))

(defn parse-entry [{:keys [redo-changes]}]
  (->> redo-changes
       (map parse-change)))

(defn safe-name [maybe-keyword]
  (if (keyword? maybe-keyword)
    (name maybe-keyword)
    maybe-keyword))

(defn select-entry
  "Selects the entry the user will see inside a list of possible entries.
  Sometimes the result will be a combination."
  [candidates]
  (let [;; Group by id and type
        entries (->> candidates
                     (remove nil?)
                     (group-by #(vector (:type %) (:operation %) (:id %)) ))

        single? (fn [coll] (= (count coll) 1))

        ;; Retrieve also by-type and by-operation
        types (group-by first (keys entries))
        operations (group-by second (keys entries))

        ;; The cases for the selection of the representative entry are a bit
        ;; convoluted. Best to read the comments to clarify.
        ;; At this stage we have cleaned the entries but we can have a batch
        ;; of operations for a single undo-entry. We want to select the
        ;; one that is most interesting for the user.
        selected-entry
        (cond
          ;; If we only have one operation over one shape we return the last change
          (single? entries)
          (-> entries (get (first (keys entries))) (last))

          ;; If we're creating an object it will have priority
          (single? (:new operations))
          (-> entries (get (first (:new operations))) (last))

          ;; If there is only a deletion of 1 group we retrieve this operation because
          ;; the others will be the children
          (single? (filter #(= :group (first %)) (:delete operations)))
          (-> entries (get (first (filter #(= :group (first %)) (:delete operations)))) (last))

          ;; If there is a move of shapes will have priority
          (single? (:move operations))
          (-> entries (get (first (:move operations))) (last))

          ;; Otherwise we could have the same operation between several
          ;; types (i.e: delete various shapes). If that happens we return
          ;; the operation with `:multiple` id
          (single? operations)
          {:type (if (every? is-shape? (keys types)) :shape :multiple)
           :id :multiple
           :operation (first (keys operations))}

          ;; Finally, if we have several operations over several shapes we return
          ;; `:multiple` for operation and type and join the last of the operations for
          ;; each shape
          :else
          {:type :multiple
           :id :multiple
           :operation :multiple})


        ;; We add to the detail the information depending on the type of operation
        detail
        (case (:operation selected-entry)
          :new (:id selected-entry)
          :modify (->> candidates
                       (filter #(= :modify (:operation %)))
                       (group-by :id)
                       (d/mapm (fn [_ v] (->> v
                                              (mapcat :detail)
                                              (map (comp safe-name :attr))
                                              (remove nil?)
                                              (into #{})))))
          :delete (->> candidates
                       (filter #(= :delete (:operation %)))
                       (map :id))
          candidates)]

    (assoc selected-entry :detail detail)))

(defn parse-entries [entries objects]
  (->> entries
       (map parse-entry)
       (map (resolve-shape-types entries objects))
       (mapv select-entry)))

(mf/defc history-entry-details [{:keys [entry]}]
  (let [{entries :items} (mf/deref workspace-undo)
        objects (mf/deref refs/workspace-page-objects)]

    [:div.history-entry-detail
     (case (:operation entry)
       :new
       (:name (get-object (:detail entry) entries objects))

       :delete
       [:ul.history-entry-details-list
        (for [id (:detail entry)]
          (let [shape-name (:name (get-object id entries objects))]
            [:li {:key id} shape-name]))]


       :modify
       [:ul.history-entry-details-list
        (for [[id attributes] (:detail entry)]
          (let [shape-name (:name (get-object id entries objects))]
            [:li {:key id}
             [:div shape-name]
             [:div (str/join ", " attributes)]]))]

       nil)]))

(mf/defc history-entry [{:keys [locale entry idx-entry disabled? current?]}]
  (let [hover? (mf/use-state false)
        show-detail? (mf/use-state false)]
    [:div.history-entry {:class (dom/classnames
                                 :disabled disabled?
                                 :current current?
                                 :hover @hover?
                                 :show-detail @show-detail?)
                         :on-mouse-enter #(reset! hover? true)
                         :on-mouse-leave #(reset! hover? false)
                         :on-click (st/emitf (dwc/undo-to-index idx-entry))}
     [:div.history-entry-summary
      [:div.history-entry-summary-icon (entry->icon entry)]
      [:div.history-entry-summary-text  (entry->message locale entry)]
      (when (:detail entry)
        [:div.history-entry-summary-button {:on-click #(when (:detail entry)
                                                         (swap! show-detail? not))}
         i/arrow-slide])]

     (when show-detail?
       [:& history-entry-details {:entry entry}])]))

(mf/defc history-toolbox []
  (let [locale (mf/deref i18n/locale)
        objects (mf/deref refs/workspace-page-objects)
        {:keys [items index]} (mf/deref workspace-undo)
        entries (parse-entries items objects)]
    [:div.history-toolbox
     [:div.history-toolbox-title (t locale "workspace.undo.title")]
     (if (empty? entries)
       [:div.history-entry-empty
        [:div.history-entry-empty-icon i/recent]
        [:div.history-entry-empty-msg (t locale "workspace.undo.empty")]]
       [:ul.history-entries
        (for [[idx-entry entry] (->> entries (map-indexed vector) reverse)] #_[i (range 0 10)]
             [:& history-entry {:key (str "entry-" idx-entry)
                                :locale locale
                                :entry entry
                                :idx-entry idx-entry
                                :current? (= idx-entry index)
                                :disabled? (> idx-entry index)}])])]))

