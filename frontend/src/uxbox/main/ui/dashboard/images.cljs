;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.dashboard.images
  (:require [cuerdas.core :as str]
            [lentes.core :as l]
            [uxbox.util.i18n :as t :refer (tr)]
            [uxbox.main.store :as st]
            [potok.core :as ptk]
            [uxbox.main.data.lightbox :as udl]
            [uxbox.main.data.images :as di]
            [uxbox.main.ui.icons :as i]
            [uxbox.util.mixins :as mx :include-macros true]
            [uxbox.main.ui.lightbox :as lbx]
            [uxbox.main.ui.keyboard :as kbd]
            [uxbox.main.ui.dashboard.header :refer (header)]
            [uxbox.util.time :as dt]
            [uxbox.util.data :as data :refer (read-string)]
            [uxbox.util.dom :as dom]))

;; --- Helpers & Constants

(def +ordering-options+
  {:name "ds.project-ordering.by-name"
   :created "ds.project-ordering.by-creation-date"})

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
          [:span {:on-click on-delete} i/trash]])])))

;; --- Nav

(defn react-count-images
  [id]
  (->> (mx/react images-ref)
       (vals)
       (filter #(= id (:collection %)))
       (count)))

(mx/defcs nav-item
  {:mixins [(mx/local) mx/static mx/reactive]}
  [own {:keys [id type name num-images] :as coll} selected?]
  (let [editable? (= type :own)
        local (:rum/local own)]
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
            {:value (if (:name @local) (:name @local) (if coll name "Storage"))
             :on-change on-input-change
             :on-key-down on-input-keyup}]
          [:span.close {:on-click on-cancel} i/close]]
         [:span.element-title
          (if coll name "Storage")])
       [:span.element-subtitle
        (tr "ds.num-elements" (t/c (or num-images (react-count-images id))))]])))

(mx/defc nav-section
  {:mixins [mx/static]}
  [type selected colls]
  (let [own? (= type :own)
        builtin? (= type :builtin)
        collections (cond->> (vals colls)
                      own? (filter #(= :own (:type %)))
                      builtin? (filter #(= :builtin (:type %)))
                      own? (sort-by :name))]
    [:ul.library-elements
     (when own?
       [:li
        [:a.btn-primary
         {:on-click #(st/emit! (di/create-collection))}
         "+ New library"]])
     (when own?
       (nav-item nil (nil? selected)))
     (for [coll collections
           :let [selected? (= (:id coll) selected)
                 key (str (:id coll))]]
       (-> (nav-item coll selected?)
           (mx/with-key key)))]))

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
      [:div.library-bar
       [:div.library-bar-inside
        [:ul.library-tabs
         [:li {:class-name (when own? "current")
               :on-click (partial select-tab :own)}
          "YOUR IMAGES"]
         [:li {:class-name (when builtin? "current")
               :on-click (partial select-tab :builtin)}
          "IMAGES STORE"]]

        (nav-section type id colls)]])))

;; --- Grid

(mx/defcs grid-form
  {:mixins [mx/static mx/reactive]}
  [own coll-id]
  (letfn [(forward-click [event]
            (dom/click (mx/ref-node own "file-input")))
          (on-file-selected [event]
            (let [files (dom/get-event-files event)]
              (st/emit! (di/create-images coll-id files))))]
    (let [uploading? (mx/react uploading?-ref)]
      [:div.grid-item.add-project {:on-click forward-click}
       (if uploading?
         [:div i/loader-pencil]
         [:span "+ New image"])
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
    [:ul.move-list
     [:li.title title]
     [:li [:a {:href "#" :on-click #(on-select % nil)} "Storage"]]
     (for [coll colls
           :let [id (:id coll)
                 name (:name coll)]]
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
      [:div.multiselect-bar
       (if editable?
         [:div.multiselect-nav
          [:span.move-item.tooltip.tooltip-top
           {:on-click on-toggle-copy :alt "Copy"}
           (when (:show-copy-tooltip @local)
             (grid-options-tooltip :selected id
                                   :title "Copy to library"
                                   :on-select on-copy))
           i/copy]
          [:span.move-item.tooltip.tooltip-top
           {:on-click on-toggle-move :alt "Move"}
           (when (:show-move-tooltip @local)
             (grid-options-tooltip :selected id
                                   :title "Move to library"
                                   :on-select on-move))
           i/move]
          (when (= 1 (count selected))
            [:span.move-item.tooltip.tooltip-top
             {:alt "Rename"
              :on-click on-rename}
             i/pencil])
          [:span.delete.tooltip.tooltip-top
           {:alt "Delete"
            :on-click on-delete}
           i/trash]]
         [:div.multiselect-nav
          [:span.move-item.tooltip.tooltip-top
           {:on-click on-toggle-copy}
           (when (:show-copy-tooltip @local)
             (grid-options-tooltip :selected id
                                   :title "Copy to library"
                                   :on-select on-copy))
           i/organize]])])))

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
    [:div.grid-item.images-th
     [:div.grid-item-th
      {:on-click toggle-selection
       :style {:background-image (str "url('" (:thumbnail image) "')")}}
      [:div.input-checkbox.check-primary
       [:input {:type "checkbox"
                :id (:id image)
                :on-click toggle-selection
                :checked selected?}]
       [:label {:for (:id image)}]]]
     [:div.item-info
      (if edition?
        [:input.element-name {:type "text"
                 :auto-focus true
                 :on-key-down on-key-down
                 :on-blur on-blur
                 :on-click on-edit
                 :default-value (:name image)}]
        [:h3 {:on-double-click on-edit}
         (:name image)])
      [:span.date
       (str "Uploaded at " (dt/format created-at "L"))]]]))

(mx/defc grid
  {:mixins [mx/static mx/reactive]}
  [{:keys [id type selected edition] :as state}]
  (let [editable? (or (= type :own) (nil? id))
        ordering (:order state :name)
        filtering (:filter state "")
        images (mx/react images-ref)
        images (->> (vals images)
                    (filter #(= id (:collection %)))
                    (filter-images-by filtering)
                    (sort-images-by ordering))]
    [:div.dashboard-grid-content
     [:div.dashboard-grid-row
      (when editable? (grid-form id))
      (for [image images
            :let [id (:id image)
                  edition? (= edition id)
                  selected? (contains? selected id)]]
        (-> (grid-item image selected? edition?)
            (mx/with-key (str id))))]]))

(mx/defc content
  {:mixins [mx/static]}
  [{:keys [selected] :as state} coll]
  [:section.dashboard-grid.library
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
      [:section.dashboard-bar.library-gap
       [:div.dashboard-info

        ;; Counter
        [:span.dashboard-images (tr "ds.num-images" (t/c icount))]

        ;; Sorting
        [:div
         [:span (tr "ds.project-ordering")]
         [:select.input-select
          {:on-change on-ordering-change
           :value (pr-str ordering)}
          (for [[key value] (seq +ordering-options+)
                :let [ovalue (pr-str key)
                      olabel (tr value)]]
            [:option {:key ovalue :value ovalue} olabel])]]
        ;; Search
        [:form.dashboard-search
         [:input.input-text
          {:key :images-search-box
           :type "text"
           :on-change on-term-change
           :auto-focus true
           :placeholder (tr "ds.project-search.placeholder")
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
    [:main.dashboard-main
     (header)
     [:section.dashboard-content
      (nav state colls)
      (menu coll)
      (content state coll)]]))
