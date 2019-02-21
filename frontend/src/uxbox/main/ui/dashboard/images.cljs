;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.dashboard.images
  (:require [cuerdas.core :as str]
            [lentes.core :as l]
            [rumext.core :as mx :include-macros true]
            [uxbox.util.i18n :as t :refer [tr]]
            [uxbox.main.store :as st]
            [uxbox.main.data.lightbox :as udl]
            [uxbox.main.data.images :as di]
            [uxbox.builtins.icons :as i]
            [uxbox.main.ui.lightbox :as lbx]
            [uxbox.main.ui.keyboard :as kbd]
            [uxbox.main.ui.dashboard.header :refer [header]]
            [uxbox.util.time :as dt]
            [uxbox.util.data :refer [read-string jscoll->vec]]
            [uxbox.util.dom :as dom]))

;; --- Helpers & Constants

(def +ordering-options+
  {:name "ds.ordering.by-name"
   :created "ds.ordering.by-creation-date"})

(defn- sort-images-by
  [ordering images]
  (case ordering
    :name (sort-by :name images)
    :created (reverse (sort-by :created-at images))
    images))

(defn- contains-term?
  [phrase term]
  (let [term (name term)]
    (str/includes? (str/lower phrase) (str/trim (str/lower term)))))

(defn- filter-images-by
  [term images]
  (if (str/blank? term)
    images
    (filter #(contains-term? (:name %) term) images)))

;; --- Refs

(def ^:private dashboard-ref
  (-> (l/in [:dashboard :images])
      (l/derive st/state)))

(def ^:private collections-ref
  (-> (l/key :images-collections)
      (l/derive st/state)))

(def ^:private images-ref
  (-> (l/key :images)
      (l/derive st/state)))

(def ^:private uploading?-ref
  (-> (l/key :uploading)
      (l/derive dashboard-ref)))

;; --- Page Title

(mx/defcs page-title
  {:mixins [(mx/local {}) mx/static mx/reactive]}
  [own {:keys [id] :as coll}]
  (let [local (:rum/local own)
        dashboard (mx/react dashboard-ref)
        own? (= :own (:type coll))
        edit? (:edit @local)]
    (letfn [(on-save [e]
              (let [dom (mx/ref-node own "input")
                    name (dom/get-inner-text dom)]
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
            [:span {:on-click on-save} ^:inline i/save]
            [:span {:on-click on-edit} ^:inline i/pencil])
          [:span {:on-click on-delete} ^:inline i/trash]])])))

;; --- Nav

(defn react-count-images
  [id]
  (->> (mx/react images-ref)
       (vals)
       (filter #(= id (:collection %)))
       (count)))

(mx/defcs nav-item
  {:mixins [(mx/local) mx/static mx/reactive]}
  [{:keys [rum/local] :as own} {:keys [id type name num-images] :as coll} selected?]
  (letfn [(on-click [event]
            (let [type (or type :own)]
              (st/emit! (di/select-collection type id))))
          (on-input-change [event]
            (let [value (dom/get-target event)
                  value (dom/get-value value)]
              (swap! local assoc :name value)))
          (on-cancel [event]
            (swap! local dissoc :name)
            (swap! local dissoc :edit))
          (on-double-click [event]
            (when (= type :own)
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
        [:span.close {:on-click on-cancel} ^:inline i/close]]
       [:span.element-title {}
        (if coll name "Storage")])
     [:span.element-subtitle
      (tr "ds.num-elements" (t/c (or num-images (react-count-images id))))]]))

(mx/defc nav-section
  {:mixins [mx/static]}
  [type selected colls]
  (let [own? (= type :own)
        builtin? (= type :builtin)
        collections (cond->> (vals colls)
                      own? (filter #(= :own (:type %)))
                      builtin? (filter #(= :builtin (:type %)))
                      own? (sort-by :name))]
    [:ul.library-elements {}
     (when own?
       [:li {}
        [:a.btn-primary
         {:on-click #(st/emit! (di/create-collection))}
         (tr "ds.images-collection.new")]])
     (when own?
       (nav-item nil (nil? selected)))
     (mx/doseq [coll collections]
       (let [selected? (= (:id coll) selected)
             key (str (:id coll))]
         (-> (nav-item coll selected?)
             (mx/with-key key))))]))

(mx/defc nav
  {:mixins [mx/static]}
  [{:keys [type id] :as state} colls]
  (let [own? (= type :own)
        builtin? (= type :builtin)]
    (letfn [(select-tab [type]
              (if own?
                (st/emit! (di/select-collection type))
                (let [coll (->> (map second colls)
                                 (filter #(= type (:type %)))
                                 (sort-by :name)
                                 (first))]
                  (if coll
                    (st/emit! (di/select-collection type (:id coll)))
                    (st/emit! (di/select-collection type))))))]
      [:div.library-bar {}
       [:div.library-bar-inside {}
        [:ul.library-tabs {}
         [:li {:class-name (when own? "current")
               :on-click (partial select-tab :own)}
          (tr "ds.your-images-title")]
         [:li {:class-name (when builtin? "current")
               :on-click (partial select-tab :builtin)}
          (tr "ds.store-images-title")]]

        (nav-section type id colls)]])))

;; --- Grid

(mx/defcs grid-form
  {:mixins [mx/static mx/reactive]}
  [own coll-id]
  (letfn [(forward-click [event]
            (dom/click (mx/ref-node own "file-input")))
          (on-file-selected [event]
            (let [files (dom/get-event-files event)
                  files (jscoll->vec files)]
              (st/emit! (di/create-images coll-id files))))]
    (let [uploading? (mx/react uploading?-ref)]
      [:div.grid-item.add-project {:on-click forward-click}
       (if uploading?
         [:div {} ^:inline i/loader-pencil]
         [:span {} (tr "ds.image-new")])
       [:input.upload-image-input
        {:style {:display "none"}
         :multiple true
         :ref "file-input"
         :value ""
         :accept "image/jpeg,image/png"
         :type "file"
         :on-change on-file-selected}]])))

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
     (mx/doseq [{:keys [id name] :as coll} colls]
       [:li {:key (str id)}
        [:a {:on-click #(on-select % id)} name]])]))

(mx/defcs grid-options
  {:mixins [(mx/local) mx/static]}
  [{:keys [rum/local] :as own} {:keys [type id] :as coll} selected]
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
       ;; If editable
       [:div.multiselect-nav {}
        [:span.move-item.tooltip.tooltip-top
         {:alt (tr "ds.multiselect-bar.copy")
          :on-click on-toggle-copy}
         (when (:show-copy-tooltip @local)
           (grid-options-tooltip :selected id
                                 :title (tr "ds.multiselect-bar.copy-to-library")
                                 :on-select on-copy))
         ^:inline i/copy]
        [:span.move-item.tooltip.tooltip-top
         {:alt (tr "ds.multiselect-bar.move")
          :on-click on-toggle-move}
         (when (:show-move-tooltip @local)
           (grid-options-tooltip :selected id
                                 :title (tr "ds.multiselect-bar.move-to-library")
                                 :on-select on-move))
         ^:inline i/move]
        (when (= 1 (count selected))
          [:span.move-item.tooltip.tooltip-top
           {:alt (tr "ds.multiselect-bar.rename")
            :on-click on-rename}
           ^:inline i/pencil])
        [:span.delete.tooltip.tooltip-top
         {:alt (tr "ds.multiselect-bar.delete")
          :on-click on-delete}
         ^:inline i/trash]]

       ;; If not editable
       [:div.multiselect-nav {}
        [:span.move-item.tooltip.tooltip-top
         {:alt (tr "ds.multiselect-bar.copy")
          :on-click on-toggle-copy}
         (when (:show-copy-tooltip @local)
           (grid-options-tooltip :selected id
                                 :title (tr "ds.multiselect-bar.copy-to-library")
                                 :on-select on-copy))
         ^:inline i/organize]])]))

(mx/defc grid-item
  {:mixins [mx/static]}
  [{:keys [id created-at] :as image} selected? edition?]
  (letfn [(toggle-selection [event]
            (st/emit! (di/toggle-image-selection id)))
          (on-key-down [event]
            (when (kbd/enter? event)
              (on-blur event)))
          (on-blur [event]
            (let [target (dom/event->target event)
                  name (dom/get-value target)]
              (st/emit! (di/update-opts :edition false)
                        (di/rename-image id name))))
          (on-edit [event]
            (dom/stop-propagation event)
            (dom/prevent-default event)
            (st/emit! (di/update-opts :edition id)))]
    [:div.grid-item.images-th {}
     [:div.grid-item-th {:on-click toggle-selection
                         :style {:background-image (str "url('" (:thumbnail image) "')")}}
      [:div.input-checkbox.check-primary {}
       [:input {:type "checkbox"
                :id (:id image)
                :on-click toggle-selection
                :checked selected?}]
       [:label {:for (:id image)}]]]
     [:div.item-info {}
      (if edition?
        [:input.element-name {:type "text"
                              :auto-focus true
                              :on-key-down on-key-down
                              :on-blur on-blur
                              :on-click on-edit
                              :default-value (:name image)}]
        [:h3 {:on-double-click on-edit} (:name image)])
      [:span.date {} (str (tr "ds.uploaded-at" (dt/format created-at "L")))]]]))

(mx/defc grid
  {:mixins [mx/static mx/reactive]}
  [{:keys [id type selected edition] :as state}]
  (let [editable? (or (= type :own) (nil? id))
        ordering (:order state :name)
        filtering (:filter state "")
        images-map (mx/react images-ref)
        images (->> (vals images-map)
                    (filter #(= id (:collection %)))
                    (filter-images-by filtering)
                    (sort-images-by ordering))]
    [:div.dashboard-grid-content {}
     [:div.dashboard-grid-row {}
      (when editable?
        (grid-form id))
      (mx/doseq [{:keys [id] :as image} images]
        (let [edition? (= edition id)
              selected? (contains? selected id)]
          (-> (grid-item image selected? edition?)
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
  [coll]
  (let [state (mx/react dashboard-ref)
        ordering (:order state :name)
        filtering (:filter state "")
        icount (count (:images coll))]
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
        [:span.dashboard-images {} (tr "ds.num-images" (t/c icount))]

        ;; Sorting
        [:div {}
         [:span {} (tr "ds.ordering")]
         [:select.input-select {:on-change on-ordering-change
                                :value (pr-str ordering)}
          (mx/doseq [[key value] (seq +ordering-options+)]
            (let [key (pr-str key)
                  label (tr value)]
              [:option {:key key :value key} label]))]]
        ;; Search
        [:form.dashboard-search {}
         [:input.input-text {:key :images-search-box
                             :type "text"
                             :on-change on-term-change
                             :auto-focus true
                             :placeholder (tr "ds.search.placeholder")
                             :value (or filtering "")}]
         [:div.clear-search {:on-click on-clear} i/close]]]])))

;; --- Images Page

(defn- images-page-will-mount
  [own]
  (let [[type id] (:rum/args own)]
    (st/emit! (di/initialize type id))
    own))

(defn- images-page-did-remount
  [old-own own]
  (let [[old-type old-id] (:rum/args old-own)
        [new-type new-id] (:rum/args own)]
    (when (or (not= old-type new-type)
              (not= old-id new-id))
      (st/emit! (di/initialize new-type new-id)))
    own))

(mx/defc images-page
  {:will-mount images-page-will-mount
   :did-remount images-page-did-remount
   :mixins [mx/static mx/reactive]}
  [_ _]
  (let [state (mx/react dashboard-ref)
        colls (mx/react collections-ref)
        coll (get colls (:id state))]
    [:main.dashboard-main {}
     (header)
     [:section.dashboard-content {}
      (nav state colls)
      (menu coll)
      (content state coll)]]))
