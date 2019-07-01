;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.dashboard.icons
  (:require [lentes.core :as l]
            [cuerdas.core :as str]
            [uxbox.main.store :as st]
            [uxbox.main.data.lightbox :as udl]
            [uxbox.main.data.icons :as di]
            [uxbox.builtins.icons :as i]
            [uxbox.main.ui.shapes.icon :as icon]
            [uxbox.main.ui.lightbox :as lbx]
            [uxbox.main.ui.dashboard.header :refer (header)]
            [uxbox.main.ui.keyboard :as kbd]
            [uxbox.util.router :as rt]
            [uxbox.util.i18n :as t :refer (tr)]
            [uxbox.util.data :refer (read-string)]
            [rumext.core :as mx :include-macros true]
            [uxbox.util.time :as dt]
            [potok.core :as ptk]
            [uxbox.util.forms :as sc]
            [uxbox.util.lens :as ul]
            [uxbox.util.i18n :refer (tr)]
            [uxbox.util.dom :as dom]))

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

(def ^:private dashboard-ref
  (-> (l/in [:dashboard :icons])
      (l/derive st/state)))

(def collections-ref
  (-> (l/key :icons-collections)
      (l/derive st/state)))

(def icons-ref
  (-> (l/key :icons)
      (l/derive st/state)))

;; --- Page Title

(mx/defcs page-title
  {:mixins [(mx/local) mx/static mx/reactive]}
  [{:keys [rum/local] :as own} {:keys [id] :as coll}]
  (let [dashboard (mx/react dashboard-ref)
        own? (= :own (:type coll))
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
      [:div.dashboard-title {}
       [:h2 {}
        (if edit?
          [:div.dashboard-title-field {}
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
         [:div.edition {}
          (if edit?
            [:span {:on-click on-save} i/save]
            [:span {:on-click on-edit} i/pencil])
          [:span {:on-click on-delete} i/trash]])])))

;; --- Nav

(defn react-count-icons
  [id]
  (->> (mx/react icons-ref)
       (vals)
       (filter #(= id (:collection %)))
       (count)))

(mx/defcs nav-item
  {:mixins [(mx/local) mx/static mx/reactive]}
  [own {:keys [id type name num-icons] :as coll} selected?]
  (let [num-icons (or num-icons (react-count-icons id))
        editable? (= type :own)
        local (:rum/local own)]
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
         [:div {}
          [:input.element-title
           {:value (if (:name @local) (:name @local) (if coll name "Storage"))
            :on-change on-input-change
            :on-key-down on-input-keyup}]
          [:span.close {:on-click on-cancel} i/close]]
         [:span.element-title {}
          (if coll name "Storage")])
       [:span.element-subtitle {}
        (tr "ds.num-elements" (t/c num-icons))]])))

(mx/defc nav-section
  {:mixins [mx/static mx/reactive]}
  [type selected colls]
  (let [own? (= type :own)
        builtin? (= type :builtin)
        colls (cond->> (vals colls)
                own? (filter #(= :own (:type %)))
                builtin? (filter #(= :builtin (:type %))))
        colls (sort-by :name colls)]
    [:ul.library-elements {}
     (when own?
       [:li {}
        [:a.btn-primary
         {:on-click #(st/emit! (di/create-collection))}
         (tr "ds.icons-collection.new")]])
     (when own?
       (nav-item nil (nil? selected)))
     (for [coll colls]
       (let [selected? (= (:id coll) selected)]
         (-> (nav-item coll selected?)
             (mx/with-key (:id coll)))))]))

(mx/defc nav
  {:mixins [mx/static]}
  [{:keys [type id] :as state} colls]
  (let [own? (= type :own)
        builtin? (= type :builtin)]
    (letfn [(select-tab [type]
              (if (= type :builtin)
                (let [colls (->> (map second colls)
                                 (filter #(= :builtin (:type %)))
                                 (sort-by :name))]
                  (st/emit! (rt/navigate :dashboard/icons {} {:type type :id (first colls)})))
                (st/emit! (rt/navigate :dashboard/icons {} {:type type}))))]
      [:div.library-bar {}
       [:div.library-bar-inside {}
        [:ul.library-tabs {}
         [:li {:class-name (when own? "current")
               :on-click (partial select-tab :own)}
          (tr "ds.your-icons-title")]
         [:li {:class-name (when builtin? "current")
               :on-click (partial select-tab :builtin)}
          (tr "ds.store-icons-title")]]

        (nav-section type id colls)]])))

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
     [:span {} (tr "ds.icon.new")]
     [:input.upload-image-input
      {:style {:display "none"}
       :multiple true
       :ref "file-input"
       :value ""
       :accept "image/svg+xml"
       :type "file"
       :on-change on-file-selected}]]))

(mx/defc grid-options-tooltip
  {:mixins [mx/reactive mx/static]}
  [& {:keys [selected on-select title]}]
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
    [:ul.move-list {}
     [:li.title {} title]
     [:li {}
      [:a {:href "#" :on-click #(on-select % nil)} "Storage"]]
     (for [{:keys [id name] :as coll} colls]
       [:li {:key (str id)}
        [:a {:on-click #(on-select % id)} name]])]))

(mx/defcs grid-options
  {:mixins [(mx/local) mx/static]}
  [own {:keys [type id] :as coll} selected]
  (let [editable? (or (= type :own) (nil? coll))
        local (:rum/local own)]
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
      [:div.multiselect-bar {}
       (if (or (= type :own) (nil? coll))
         ;; if editable
         [:div.multiselect-nav {}
          [:span.move-item.tooltip.tooltip-top
           {:alt (tr "ds.multiselect-bar.copy")
            :on-click on-toggle-copy}
           (when (:show-copy-tooltip @local)
             (grid-options-tooltip :selected id
                                   :title (tr "ds.multiselect-bar.copy-to-library")
                                   :on-select on-copy))
           i/copy]
          [:span.move-item.tooltip.tooltip-top
           {:alt (tr "ds.multiselect-bar.move")
            :on-click on-toggle-move}
           (when (:show-move-tooltip @local)
             (grid-options-tooltip :selected id
                                   :title (tr "ds.multiselect-bar.move-to-library")
                                   :on-select on-move))
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
             (grid-options-tooltip :selected id
                                   :title (tr "ds.multiselect-bar.copy-to-library")
                                   :on-select on-copy))
           i/organize]])])))

(mx/defc grid-item
  {:mixins [mx/static]}
  [{:keys [id created-at] :as icon} selected? edition?]
  (letfn [(toggle-selection [event]
            (st/emit! (di/toggle-icon-selection id)))
          (toggle-selection-shifted [event]
            (when (kbd/shift? event)
              (toggle-selection event)))
          (on-blur [event]
            (let [target (dom/event->target event)
                  name (dom/get-value target)]
              (st/emit! (di/update-opts :edition false)
                        (di/rename-icon id name))))
          (on-key-down [event]
            (when (kbd/enter? event)
              (on-blur event)))
          (ignore-click [event]
            (dom/stop-propagation event)
            (dom/prevent-default event))
          (on-edit [event]
            (dom/stop-propagation event)
            (dom/prevent-default event)
            (st/emit! (di/update-opts :edition id)))]
    [:div.grid-item.small-item.project-th
     {:on-click toggle-selection
      :id (str "grid-item-" id)}
     [:div.input-checkbox.check-primary {}
      [:input {:type "checkbox"
               :id (:id icon)
               :on-click toggle-selection
               :checked selected?}]
      [:label {:for (:id icon)}]]
     [:span.grid-item-image (icon/icon-svg icon)]
     [:div.item-info
      {:on-click ignore-click}
      (if edition?
        [:input.element-name {:type "text"
                              :auto-focus true
                              :on-key-down on-key-down
                              :on-blur on-blur
                              :on-click on-edit
                              :default-value (:name icon)}]
        [:h3 {:on-double-click on-edit}
         (:name icon)])
      (str (tr "ds.uploaded-at" (dt/format created-at "DD/MM/YYYY")))]]))

(mx/defc grid
  {:mixins [mx/static mx/reactive]}
  [{:keys [selected edition id type] :as state}]
  (let [editable? (or (= type :own) (nil? id))
        ordering (:order state :name)
        filtering (:filter state "")
        icons (mx/react icons-ref)
        icons (->> (vals icons)
                   (filter #(= id (:collection %)))
                   (filter-icons-by filtering)
                   (sort-icons-by ordering))]
    [:div.dashboard-grid-content {}
     [:div.dashboard-grid-row {}
      (when editable? (grid-form id))
      (for [{:keys [id] :as icon} icons]
        (let [edition? (= edition id)
              selected? (contains? selected id)]
          (-> (grid-item icon selected? edition?)
              (mx/with-key (str id)))))]]))

(mx/defc content
  {:mixins [mx/static]}
  [{:keys [selected] :as state} coll]
  [:section.dashboard-grid.library {}
   (page-title coll)
   (grid state)
   (when (seq selected)
     (grid-options coll selected))])

;; --- Menu

(mx/defc menu
  {:mixins [mx/static mx/reactive]}
  [state {:keys [id num-icons] :as coll}]
  (let [ordering (:order state :name)
        filtering (:filter state "")
        num-icons (or num-icons (react-count-icons id))]
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
      [:section.dashboard-bar.library-gap {}
       [:div.dashboard-info {}

        ;; Counter
        [:span.dashboard-icons {} (tr "ds.num-icons" (t/c num-icons))]

        ;; Sorting
        [:div {}
         [:span {} (tr "ds.ordering")]
         [:select.input-select
          {:on-change on-ordering-change
           :value (pr-str ordering)}
          (for [[key value] (seq +ordering-options+)]
            (let [key (pr-str key)]
              [:option {:key key :value key} (tr value)]))]]
        ;; Search
        [:form.dashboard-search {}
         [:input.input-text
          {:key :icons-search-box
           :type "text"
           :on-change on-term-change
           :auto-focus true
           :placeholder (tr "ds.search.placeholder")
           :value (or filtering "")}]
         [:div.clear-search {:on-click on-clear} i/close]]]])))

;; --- Icons Page

(defn- icons-page-will-mount
  [own]
  (let [[type id] (:rum/args own)]
    (st/emit! (di/initialize type id))
    own))

(defn- icons-page-did-remount
  [old-own own]
  (let [[old-type old-id] (:rum/args old-own)
        [new-type new-id] (:rum/args own)]
    (when (or (not= old-type new-type)
              (not= old-id new-id))
      (st/emit! (di/initialize new-type new-id)))
    own))

(mx/defc icons-page
  {:will-mount icons-page-will-mount
   :did-remount icons-page-did-remount
   :mixins [mx/static mx/reactive]}
  []
  (let [state (mx/react dashboard-ref)
        colls (mx/react collections-ref)
        coll (get colls (:id state))]
    [:main.dashboard-main {}
     (header)
     [:section.dashboard-content {}
      (nav state colls)
      (menu state coll)
      (content state coll)]]))
