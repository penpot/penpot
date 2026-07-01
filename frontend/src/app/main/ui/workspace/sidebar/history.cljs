;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.sidebar.history
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.time :as ct]
   [app.main.data.workspace.undo :as dwu]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.ds.product.empty-state :refer [empty-state*]]
   [app.main.ui.icons :as deprecated-icon]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr] :as i18n]
   [cuerdas.core :as str]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

(def workspace-undo
  (l/derived :workspace-undo st/state))

(def workspace-actions
  (l/derived :workspace-actions st/state))

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
  [type multiple?]
  (let [arity (if multiple? "multiple" "single")
        attribute (name (or type :multiple))]
    ;; Execution time translation strings:
    ;;   (tr "workspace.undo.entry.multiple.circle")
    ;;   (tr "workspace.undo.entry.multiple.color")
    ;;   (tr "workspace.undo.entry.multiple.component")
    ;;   (tr "workspace.undo.entry.multiple.curve")
    ;;   (tr "workspace.undo.entry.multiple.frame")
    ;;   (tr "workspace.undo.entry.multiple.group")
    ;;   (tr "workspace.undo.entry.multiple.media")
    ;;   (tr "workspace.undo.entry.multiple.multiple")
    ;;   (tr "workspace.undo.entry.multiple.page")
    ;;   (tr "workspace.undo.entry.multiple.path")
    ;;   (tr "workspace.undo.entry.multiple.rect")
    ;;   (tr "workspace.undo.entry.multiple.shape")
    ;;   (tr "workspace.undo.entry.multiple.text")
    ;;   (tr "workspace.undo.entry.multiple.typography")
    ;;   (tr "workspace.undo.entry.single.circle")
    ;;   (tr "workspace.undo.entry.single.color")
    ;;   (tr "workspace.undo.entry.single.component")
    ;;   (tr "workspace.undo.entry.single.curve")
    ;;   (tr "workspace.undo.entry.single.frame")
    ;;   (tr "workspace.undo.entry.single.group")
    ;;   (tr "workspace.undo.entry.single.image")
    ;;   (tr "workspace.undo.entry.single.media")
    ;;   (tr "workspace.undo.entry.single.multiple")
    ;;   (tr "workspace.undo.entry.single.page")
    ;;   (tr "workspace.undo.entry.single.path")
    ;;   (tr "workspace.undo.entry.single.rect")
    ;;   (tr "workspace.undo.entry.single.shape")
    ;;   (tr "workspace.undo.entry.single.text")
    ;;   (tr "workspace.undo.entry.single.typography")
    (tr (str/format "workspace.undo.entry.%s.%s" arity attribute))))

(defn entry->message [entry]
  (let [value (entry-type->message (:type entry) (= :multiple (:id entry)))]
    (case (:operation entry)
      :new (tr "workspace.undo.entry.new" value)
      :modify (tr "workspace.undo.entry.modify" value)
      :delete (tr "workspace.undo.entry.delete" value)
      :move (tr "workspace.undo.entry.move" value)
      (tr "workspace.undo.entry.unknown" value))))

(defn entry->icon [{:keys [type]}]
  (case type
    :page deprecated-icon/document
    :shape deprecated-icon/svg
    :rect deprecated-icon/rectangle
    :circle deprecated-icon/ellipse
    :text deprecated-icon/text
    :path deprecated-icon/path
    :frame deprecated-icon/board
    :group deprecated-icon/group
    :color deprecated-icon/drop-icon
    :typography deprecated-icon/text-palette
    :component deprecated-icon/component
    :media deprecated-icon/img
    :image deprecated-icon/img
    deprecated-icon/svg))

(defn is-shape? [type]
  (contains? #{:shape :rect :circle :text :path :frame :group} type))

(defn parse-entry [{:keys [redo-changes]}]
  (->> redo-changes
       (map parse-change)))

(defn- short-id
  "Build a short git-like label for an undo entry. Derives it from the
  entry's `:undo-group` uuid (which the undo subsystem already generates
  for every action). Issue #7660."
  [entry]
  (when-let [group (:undo-group entry)]
    (let [s (str group)]
      (-> s
          (str/replace #"[^0-9a-f]" "")
          (subs 0 (min 7 (count s)))))))

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
                     (group-by #(vector (:type %) (:operation %) (:id %))))

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
  ;; Propagate per-entry metadata (timestamp, undo-group, author) onto
  ;; the summarized result so the UI can show when the action happened,
  ;; a short stable identifier, and who made the change. Issue #7660.
  (mapv (fn [raw-entry]
          (-> (parse-entry raw-entry)
              ((resolve-shape-types entries objects))
              (select-entry)
              (assoc :timestamp  (:timestamp  raw-entry)
                     :undo-group (:undo-group raw-entry)
                     :by         (:by         raw-entry))))
        entries))

(defn- action->entry
  "Convert a server-side action (from :workspace-actions) into the
   same shape as an undo entry so it can be rendered with the same
   parsing pipeline. Issue #10495."
  [profiles action]
  (let [profile (get profiles (:profile-id action))]
    {:redo-changes (:changes action)
     :undo-changes []
     :timestamp    (:created-at action)
     :undo-group   (:id action)
     :by           (or (:fullname profile)
                       (:email profile)
                       (dm/str (:profile-id action)))}))

(mf/defc history-entry-details* [{:keys [entry]}]
  (let [{entries :items} (mf/deref workspace-undo)
        objects (mf/deref refs/workspace-page-objects)]

    [:div {:class (stl/css :history-entry-detail)}
     (case (:operation entry)
       :new
       (:name (get-object (:detail entry) entries objects))

       :delete
       [:ul {:class (stl/css :history-entry-details-list)}
        (for [id (:detail entry)]
          (let [shape-name (:name (get-object id entries objects))]
            [:li {:key id} shape-name]))]


       :modify
       [:ul {:class (stl/css :history-entry-details-list)}
        (for [[id attributes] (:detail entry)]
          (let [shape-name (:name (get-object id entries objects))]
            [:li {:key id}
             [:div shape-name]
             [:div (str/join ", " attributes)]]))]

       nil)]))

(mf/defc history-entry*
  {::mf/props :obj}
  [{:keys [entry idx-entry is-disabled is-current]}]
  (let [hover?         (mf/use-state false)
        show-detail?   (mf/use-state false)

        relative-time  (ct/timeago (:timestamp entry))
        label          (short-id entry)
        author         (:by entry)

        toggle-show-detail
        (mf/use-fn
         (fn [event]
           (let [has-entry? (-> (dom/get-current-target event)
                                (dom/get-data "has-entry")
                                (parse-boolean))]
             (dom/stop-propagation event)
             (when has-entry?
               (swap! show-detail? not)))))]
    [:div {:class (stl/css-case :history-entry true
                                :disabled is-disabled
                                :current is-current
                                :hover @hover?
                                :show-detail @show-detail?)
           :on-pointer-enter #(reset! hover? true)
           :on-pointer-leave #(reset! hover? false)
           :on-click #(st/emit! (dwu/undo-to-index idx-entry))}

     [:div {:class (stl/css :history-entry-summary)}
      [:div {:class (stl/css :history-entry-summary-icon)}
       (entry->icon entry)]
      [:div {:class (stl/css :history-entry-summary-text)}
       [:div {:class (stl/css :history-entry-title)}
        (entry->message entry)]
       ;; Metadata row: short identifier, relative timestamp, and
       ;; author. Rendered as plain inline text so the natural word
       ;; spacing between tokens ("17bc430 · just now by <Name>") is
       ;; preserved without relying on flex gap. Issue #7660.
       (when (or label relative-time author)
         [:div {:class (stl/css :history-entry-meta)}
          (when label
            [:span {:class (stl/css :history-entry-hash)
                    :title (dm/str (:undo-group entry))}
             label])
          (when (and label relative-time)
            [:span {:class (stl/css :history-entry-meta-sep)} " · "])
          (when relative-time
            [:span {:class (stl/css :history-entry-time)}
             relative-time])
          (when (and (or label relative-time) author) " ")
          (when author
            [:span {:class (stl/css :history-entry-author)}
             (tr "workspace.undo.entry.by" author)])])]
      (when (:detail entry)
        [:div {:class (stl/css-case :history-entry-summary-button true
                                    :button-opened @show-detail?)
               :on-click toggle-show-detail
               :data-has-entry (dm/str (not (nil? (:detail entry))))}
         deprecated-icon/arrow])]

     (when @show-detail?
       [:> history-entry-details* {:entry entry}])]))

(mf/defc action-entry*
  "Render a single action entry from another user. Similar to
   history-entry* but read-only (no undo click) and always shows
   the author. Issue #10495."
  {::mf/props :obj}
  [{:keys [entry]}]
  (let [hover?         (mf/use-state false)
        show-detail?   (mf/use-state false)
        relative-time  (ct/timeago (:timestamp entry))
        author         (:by entry)

        toggle-show-detail
        (mf/use-fn
         (fn [event]
           (let [has-entry? (-> (dom/get-current-target event)
                                (dom/get-data "has-entry")
                                (parse-boolean))]
             (dom/stop-propagation event)
             (when has-entry?
               (swap! show-detail? not)))))]

    [:div {:class (stl/css-case :history-entry true
                                :action-entry true
                                :hover @hover?
                                :show-detail @show-detail?)
           :on-pointer-enter #(reset! hover? true)
           :on-pointer-leave #(reset! hover? false)}

     [:div {:class (stl/css :history-entry-summary)}
      [:div {:class (stl/css :history-entry-summary-icon)}
       (entry->icon entry)]
      [:div {:class (stl/css :history-entry-summary-text)}
       [:div {:class (stl/css :history-entry-title)}
        (entry->message entry)]
       (when (or relative-time author)
         [:div {:class (stl/css :history-entry-meta)}
          (when relative-time
            [:span {:class (stl/css :history-entry-time)}
             relative-time])
          (when (and relative-time author) " ")
          (when author
            [:span {:class (stl/css :history-entry-author)}
             (tr "workspace.undo.entry.by" author)])])]
      (when (:detail entry)
        [:div {:class (stl/css-case :history-entry-summary-button true
                                    :button-opened @show-detail?)
               :on-click toggle-show-detail
               :data-has-entry (dm/str (not (nil? (:detail entry))))}
         deprecated-icon/arrow])]

     (when @show-detail?
       [:> history-entry-details* {:entry entry}])]))

(mf/defc history-toolbox*
  []
  (let [profiles (mf/deref refs/profiles)
        objects  (mf/deref refs/workspace-page-objects)
        {:keys [items index]} (mf/deref workspace-undo)
        {:keys [data] :or {data nil}} (mf/deref workspace-actions)

        ;; Build an index of undo-group -> position in items for local lookup
        item-index
        (mf/with-memo [items]
          (into {}
                (map-indexed (fn [i item] [(:undo-group item) i])
                             items)))

        ;; Parse local undo entries (one-to-one with items order)
        local-entries (parse-entries items objects)

        ;; Convert server actions to entry format and parse them,
        ;; marking them as remote so they render as read-only
        action-entries
        (mf/with-memo [data profiles objects]
          (when (seq data)
            (let [action-items (mapv (partial action->entry profiles) data)]
              (mapv #(assoc % :remote true)
                    (parse-entries action-items objects)))))

        ;; Build combined list sorted by timestamp descending
        all-entries
        (mf/with-memo [local-entries action-entries]
          (let [combined (into (vec action-entries) local-entries)]
            (->> combined
                 (sort-by :timestamp (fn [a b] (compare b a)))
                 (vec))))]

    [:div {:class (stl/css :history-toolbox)}
     (if (and (empty? local-entries) (empty? action-entries))
       [:div {:class (stl/css :history-entry-empty)}
        [:> empty-state* {:icon i/history
                          :text (tr "workspace.undo.empty")}]]
       [:ul {:class (stl/css :history-entries)}
        (for [[idx entry] (map-indexed vector all-entries)]
          (if (:remote entry)
            [:> action-entry* {:key (str "action-" idx) :entry entry}]
            (let [undo-idx (get item-index (:undo-group entry) idx)]
              [:> history-entry*
               {:key (str "entry-" idx)
                :entry entry
                :idx-entry undo-idx
                :is-current (= undo-idx index)
                 :is-disabled (> undo-idx index)}])))])]))



