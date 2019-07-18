;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>
;; Copyright (c) 2015-2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.ui.dashboard.icons
  (:require
   [cuerdas.core :as str]
   [lentes.core :as l]
   [potok.core :as ptk]
   [rumext.core :as mx :include-macros true]
   [uxbox.builtins.icons :as i]
   [uxbox.main.data.icons :as di]
   [uxbox.main.data.lightbox :as udl]
   [uxbox.main.store :as st]
   [uxbox.main.ui.dashboard.header :refer (header)]
   [uxbox.main.ui.keyboard :as kbd]
   [uxbox.main.ui.lightbox :as lbx]
   [uxbox.main.ui.shapes.icon :as icon]
   [uxbox.util.data :refer (read-string)]
   [uxbox.util.dom :as dom]
   [uxbox.util.forms :as sc]
   [uxbox.util.i18n :as t :refer (tr)]
   [uxbox.util.i18n :refer (tr)]
   [uxbox.util.lens :as ul]
   [uxbox.util.router :as rt]
   [uxbox.util.time :as dt]))

;; --- Helpers & Constants

(def +ordering-options+
  {:name "ds.ordering.by-name"
   :created "ds.ordering.by-creation-date"})

(defn- sort-icons-by
  [ordering icons]
  (case ordering
    :name (sort-by :name icons)
    :created (reverse (sort-by :created-at icons))
    icons))

(defn- contains-term?
  [phrase term]
  {:pre [(string? phrase)
         (string? term)]}
  (let [term (name term)]
    (str/includes? (str/lower phrase) (str/trim (str/lower term)))))

(defn- filter-icons-by
  [term icons]
  (if (str/blank? term)
    icons
    (filter #(contains-term? (:name %) term) icons)))

;; --- Refs

(def collections-ref
  (-> (l/key :icons-collections)
      (l/derive st/state)))

(def opts-ref
  (-> (l/in [:dashboard :icons])
      (l/derive st/state)))

;; TODO: remove when sidebar is refactored
(def icons-ref
  (-> (l/key :icons)
      (l/derive st/state)))

;; --- Page Title

(mx/def page-title
  :mixins [(mx/local) mx/static]

  :render
  (fn [{:keys [::mx/local] :as own} {:keys [id type] :as coll}]
    (let [own? (= :own (:type coll))
          edit? (:edit @local)]
      (letfn [(on-save [e]
                (let [dom (mx/ref-node own "input")
                      name (.-innerText dom)]
                  (st/emit! (di/rename-collection id (str/trim name)))
                  (swap! local assoc :edit false)))
              (on-cancel [e]
                (swap! local assoc :edit false))
              (on-edit [e]
                (swap! local assoc :edit true))
              (on-input-keydown [e]
                (cond
                  (kbd/esc? e) (on-cancel e)
                  (kbd/enter? e)
                  (do
                    (dom/prevent-default e)
                    (dom/stop-propagation e)
                    (on-save e))))
              (delete []
                (st/emit! (di/delete-collection id)))
              (on-delete []
                (udl/open! :confirm {:on-accept delete}))]
        [:div.dashboard-title
         [:h2
          (if edit?
            [:div.dashboard-title-field
             [:span.edit
              {:content-editable true
               :ref "input"
               :on-key-down on-input-keydown}
              (:name coll)]
             [:span.close {:on-click on-cancel} i/close]]
            (if own?
              [:span.dashboard-title-field
               {:on-double-click on-edit}
               (:name coll)]
              [:span.dashboard-title-field
               (:name coll "Storage")]))]
         (when (and own? coll)
           [:div.edition
            (if edit?
              [:span {:on-click on-save} i/save]
              [:span {:on-click on-edit} i/pencil])
            [:span {:on-click on-delete} i/trash]])]))))

;; --- Nav

(defn num-icons-ref
  [id]
  (let [selector (fn [icons] (count (filter #(= id (:collection %)) (vals icons))))]
    (-> (comp (l/key :icons)
              (l/lens selector))
        (l/derive st/state))))

(mx/def nav-item
  :key-fn :id
  :mixins [(mx/local) mx/static mx/reactive]

  :init
  (fn [own {:keys [id] :as props}]
    (assoc own ::num-icons-ref (num-icons-ref id)))

  :render
  (fn [{:keys [::mx/local] :as own}
       {:keys [id type name num-icons ::selected?] :as coll}]
    (let [num-icons (or num-icons (mx/react (::num-icons-ref own)))
          editable? (= type :own)]
      (letfn [(on-click [event]
                (let [type (or type :own)]
                  (st/emit! (rt/navigate :dashboard/icons {} {:type type :id id}))))
              (on-input-change [event]
                (let [value (dom/get-target event)
                      value (dom/get-value value)]
                  (swap! local assoc :name value)))
              (on-cancel [event]
                (swap! local dissoc :name)
                (swap! local dissoc :edit))
              (on-double-click [event]
                (when editable?
                  (swap! local assoc :edit true)))
              (on-input-keyup [event]
                (when (kbd/enter? event)
                  (let [value (dom/get-target event)
                        value (dom/get-value value)]
                    (st/emit! (di/rename-collection id (str/trim (:name @local))))
                    (swap! local assoc :edit false))))]
        [:li {:on-click on-click
              :on-double-click on-double-click
              :class-name (when selected? "current")}
         (if (:edit @local)
           [:div
            [:input.element-title
             {:value (if (:name @local) (:name @local) (if id name "Storage"))
              :on-change on-input-change
              :on-key-down on-input-keyup}]
            [:span.close {:on-click on-cancel} i/close]]
           [:span.element-title
            (if id name "Storage")])
         [:span.element-subtitle
          (tr "ds.num-elements" (t/c num-icons))]]))))

(mx/def nav
  :mixins [mx/static mx/reactive]

  :render
  (fn [own {:keys [id type] :as props}]
    (let [own? (= type :own)
          builtin? (= type :builtin)
          colls (mx/react collections-ref)
          select-tab (fn [type]
                       (if-let [coll (->> (vals colls)
                                          (filter #(= type (:type %)))
                                          (sort-by :created-at)
                                          (first))]
                         (st/emit! (rt/nav :dashboard/icons nil {:type type :id (:id coll)}))
                         (st/emit! (rt/nav :dashboard/icons nil {:type type}))))]
      [:div.library-bar
       [:div.library-bar-inside
        [:ul.library-tabs
         [:li {:class-name (when own? "current")
               :on-click (partial select-tab :own)}
          (tr "ds.your-icons-title")]
         [:li {:class-name (when builtin? "current")
               :on-click (partial select-tab :builtin)}
          (tr "ds.store-icons-title")]]
        [:ul.library-elements
         (when own?
           [:li
            [:a.btn-primary {:on-click #(st/emit! (di/create-collection))}
             (tr "ds.icons-collection.new")]])
         (when own?
           (nav-item {::selected? (nil? id)}))
         (for [coll (cond->> (vals colls)
                      own? (filter #(= :own (:type %)))
                      builtin? (filter #(= :builtin (:type %)))
                      true (sort-by :name))]
           (let [selected? (= (:id coll) id)]
             (nav-item (assoc coll ::selected? selected?))))]]])))

;; --- Grid

(mx/defcs grid-form
  {:mixins [mx/static]}
  [own coll-id]
  (letfn [(forward-click [event]
            (dom/click (mx/ref-node own "file-input")))
          (on-file-selected [event]
            (let [files (dom/get-event-files event)]
              (st/emit! (di/create-icons coll-id files))))]
    [:div.grid-item.small-item.add-project {:on-click forward-click}
     [:span (tr "ds.icon.new")]
     [:input.upload-image-input
      {:style {:display "none"}
       :multiple true
       :ref "file-input"
       :value ""
       :accept "image/svg+xml"
       :type "file"
       :on-change on-file-selected}]]))

(mx/def grid-options-tooltip
  :mixins [mx/reactive mx/static]

  :render
  (fn [own {:keys [selected on-select title]}]
    {:pre [(uuid? selected)
           (fn? on-select)
           (string? title)]}
    (let [colls (mx/react collections-ref)
          colls (->> (vals colls)
                     (filter #(= :own (:type %)))
                     (remove #(= selected (:id %)))
                     (sort-by :name colls))
          on-select (fn [event id]
                      (dom/prevent-default event)
                      (dom/stop-propagation event)
                      (on-select id))]
      [:ul.move-list
       [:li.title title]
       [:li
        [:a {:href "#" :on-click #(on-select % nil)} "Storage"]]
       (for [{:keys [id name] :as coll} colls]
         [:li {:key (pr-str id)}
          [:a {:on-click #(on-select % id)} name]])])))

(mx/def grid-options
  :mixins [(mx/local) mx/static]

  :render
  (fn [{:keys [::mx/local] :as own}
       {:keys [id type selected] :as props}]
    (letfn [(delete []
              (st/emit! (di/delete-selected)))
            (on-delete [event]
              (udl/open! :confirm {:on-accept delete}))
            (on-toggle-copy [event]
              (swap! local update :show-copy-tooltip not))
            (on-toggle-move [event]
              (swap! local update :show-move-tooltip not))
            (on-copy [selected]
              (swap! local assoc
                     :show-move-tooltip false
                     :show-copy-tooltip false)
              (st/emit! (di/copy-selected selected)))
            (on-move [selected]
              (swap! local assoc
                     :show-move-tooltip false
                     :show-copy-tooltip false)
              (st/emit! (di/move-selected selected)))
            (on-rename [event]
              (let [selected (first selected)]
                (st/emit! (di/update-opts :edition selected))))]

      ;; MULTISELECT OPTIONS BAR
      [:div.multiselect-bar
       (if (or (= type :own) (nil? id))
         ;; if editable
         [:div.multiselect-nav {}
          [:span.move-item.tooltip.tooltip-top
           {:alt (tr "ds.multiselect-bar.copy")
            :on-click on-toggle-copy}
           (when (:show-copy-tooltip @local)
             (grid-options-tooltip {:selected id
                                    :title (tr "ds.multiselect-bar.copy-to-library")
                                    :on-select on-copy}))
           i/copy]
          [:span.move-item.tooltip.tooltip-top
           {:alt (tr "ds.multiselect-bar.move")
            :on-click on-toggle-move}
           (when (:show-move-tooltip @local)
             (grid-options-tooltip {:selected id
                                    :title (tr "ds.multiselect-bar.move-to-library")
                                    :on-select on-move}))
           i/move]
          (when (= 1 (count selected))
            [:span.move-item.tooltip.tooltip-top
             {:alt (tr "ds.multiselect-bar.rename")
              :on-click on-rename}
             i/pencil])
          [:span.delete.tooltip.tooltip-top
           {:alt (tr "ds.multiselect-bar.delete")
            :on-click on-delete}
           i/trash]]

         ;; if not editable
         [:div.multiselect-nav
          [:span.move-item.tooltip.tooltip-top
           {:alt (tr "ds.multiselect-bar.copy")
            :on-click on-toggle-copy}
           (when (:show-copy-tooltip @local)
             (grid-options-tooltip {:selected id
                                    :title (tr "ds.multiselect-bar.copy-to-library")
                                    :on-select on-copy}))
           i/organize]])])))

(mx/def grid-item
  :key-fn :id
  :mixins [mx/static]
  :render
  (fn [own {:keys [id created-at ::selected? ::edition?] :as icon}]
    (letfn [(toggle-selection [event]
              (prn "toggle-selection")
              (st/emit! (di/toggle-icon-selection id)))
            (on-key-down [event]
              (when (kbd/enter? event)
                (on-blur event)))
            (on-blur [event]
              (let [target (dom/event->target event)
                    name (dom/get-value target)]
                (st/emit! (di/update-opts :edition false)
                          (di/rename-icon id name))))
            (ignore-click [event]
              (dom/stop-propagation event)
              (dom/prevent-default event))
            (on-edit [event]
              (dom/stop-propagation event)
              (dom/prevent-default event)
              (st/emit! (di/update-opts :edition id)))]
      [:div.grid-item.small-item.project-th {:id (str "grid-item-" id)}
       [:div.input-checkbox.check-primary
        [:input {:type "checkbox"
                 :id (:id icon)
                 :on-change toggle-selection
                 :checked selected?}]
        [:label {:for (:id icon)}]]
       [:span.grid-item-image (icon/icon-svg icon)]
       [:div.item-info {:on-click ignore-click}
        (if edition?
          [:input.element-name {:type "text"
                                :auto-focus true
                                :on-key-down on-key-down
                                :on-blur on-blur
                                :on-click on-edit
                                :default-value (:name icon)}]
          [:h3 {:on-double-click on-edit}
           (:name icon)])
        (str (tr "ds.uploaded-at" (dt/format created-at "DD/MM/YYYY")))]])))

(mx/def grid
  :mixins [mx/reactive]
  :init
  (fn [own {:keys [id] :as props}]
    (let [selector (fn [icons]
                     (->> (vals icons)
                          (filter #(= id (:collection %)))))]
      (assoc own ::icons-ref (-> (comp (l/key :icons)
                                       (l/lens selector))
                                 (l/derive st/state)))))

  :render
  (fn [own {:keys [selected edition id type] :as props}]
    (let [editable? (or (= type :own) (nil? id))
          icons (->> (mx/react (::icons-ref own))
                     (filter-icons-by (:filter props ""))
                     (sort-icons-by (:order props :name)))]

      [:div.dashboard-grid-content
       [:div.dashboard-grid-row
        (when editable? (grid-form id))
        (for [icon icons]
          (let [edition? (= edition (:id icon))
                selected? (contains? selected (:id icon))]
            (grid-item (assoc icon ::selected? selected? ::edition? edition?))))]])))

;; --- Menu

(mx/def menu
  :mixins [mx/static mx/reactive]

  :init
  (fn [own {:keys [id] :as props}]
    (assoc own ::num-icons-ref (num-icons-ref id)))

  :render
  (fn [own props]
    (let [{:keys [id num-icons] :as coll} (::coll props)
          num-icons (or num-icons (mx/react (::num-icons-ref own)))]
      (letfn [(on-term-change [event]
                (let [term (-> (dom/get-target event)
                               (dom/get-value))]
                  (st/emit! (di/update-opts :filter term))))
              (on-ordering-change [event]
                (let [value (dom/event->value event)
                      value (read-string value)]
                  (st/emit! (di/update-opts :order value))))
              (on-clear [event]
                (st/emit! (di/update-opts :filter "")))]
        [:section.dashboard-bar.library-gap
         [:div.dashboard-info
          ;; Counter
          [:span.dashboard-icons (tr "ds.num-icons" (t/c num-icons))]

          ;; Sorting
          [:div
           [:span (tr "ds.ordering")]
           [:select.input-select {:on-change on-ordering-change
                                  :value (pr-str (:order props :name))}
            (for [[key value] (seq +ordering-options+)]
              [:option {:key key :value (pr-str key)} (tr value)])]]
          ;; Search
          [:form.dashboard-search
           [:input.input-text {:key :icons-search-box
                               :type "text"
                               :on-change on-term-change
                               :auto-focus true
                               :placeholder (tr "ds.search.placeholder")
                               :value (:filter props "")}]
           [:div.clear-search {:on-click on-clear}
            i/close]]]]))))

(mx/def content
  :mixins [mx/reactive mx/static]

  :init
  (fn [own {:keys [id] :as props}]
    (assoc own ::coll-ref (-> (l/in [:icons-collections id])
                              (l/derive st/state))))

  :render
  (fn [own props]
    (let [opts (mx/react opts-ref)
          coll (mx/react (::coll-ref own))
          props (merge opts props)]
      [:*
       (menu (assoc props ::coll coll))
       [:section.dashboard-grid.library
        (page-title coll)
        (grid props)
        (when (seq (:selected opts))
          (grid-options props))]])))


;; --- Icons Page

(mx/def icons-page
  :key-fn identity
  :mixins #{mx/static mx/reactive}

  :init
  (fn [own props]
    (let [{:keys [type id]} (::mx/props own)]
      (st/emit! (di/initialize type id))
      own))

  :render
  (fn [own {:keys [type] :as props}]
    (let [type (or type :own)
          props (assoc props :type type)]
      [:section.dashboard-content
       (nav props)
       (content props)])))
