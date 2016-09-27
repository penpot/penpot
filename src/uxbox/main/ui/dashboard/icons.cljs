;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.dashboard.icons
  (:require [sablono.core :refer-macros [html]]
            [rum.core :as rum]
            [lentes.core :as l]
            [cuerdas.core :as str]
            [uxbox.main.state :as st]
            [uxbox.main.library :as library]
            [uxbox.main.data.lightbox :as udl]
            [uxbox.main.data.icons :as di]
            [uxbox.main.ui.icons :as i]
            [uxbox.main.ui.shapes.icon :as icon]
            [uxbox.main.ui.lightbox :as lbx]
            [uxbox.main.ui.dashboard.header :refer (header)]
            [uxbox.main.ui.keyboard :as kbd]
            [uxbox.util.i18n :as t :refer (tr)]
            [uxbox.util.data :refer (read-string)]
            [uxbox.util.mixins :as mx :include-macros true]
            [uxbox.util.rstore :as rs]
            [uxbox.util.schema :as sc]
            [uxbox.util.lens :as ul]
            [uxbox.util.i18n :refer (tr)]
            [uxbox.util.dom :as dom]))

;; --- Helpers & Constants

(def +ordering-options+
  {:name "ds.project-ordering.by-name"
   :created "ds.project-ordering.by-creation-date"})

(defn- sort-icons-by
  [ordering icons]
  (case ordering
    :name (sort-by :name icons)
    :created (reverse (sort-by :created-at icons))
    icons))

(defn- contains-term?
  [phrase term]
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

(def ^:private collections-map-ref
  (-> (comp (l/key :icon-colls-by-id)
            (ul/merge library/+icon-collections-by-id+))
      (l/derive st/state)))

(def ^:private collections-ref
  (-> (l/lens vals)
      (l/derive collections-map-ref)))

(defn- focus-collection
  [id]
  (-> (l/key id)
      (l/derive collections-map-ref)))

;; --- Page Title

(mx/defcs page-title
  {:mixins [(mx/local {}) mx/static mx/reactive]}
  [own {:keys [id] :as coll}]
  (let [local (:rum/local own)
        dashboard (mx/react dashboard-ref)
        own? (= :builtin (:type coll))
        edit? (:edit @local)]
    (letfn [(on-save [e]
              (let [dom (mx/ref-node own "input")
                    name (.-innerText dom)]
                #_(rs/emit! (di/rename-collection id (str/trim name)))
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
              #_(rs/emit! (di/delete-collection (:id coll))))
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
          [:span.dashboard-title-field
           {:on-double-click on-edit}
           (:name coll)])]
       (if (and (not own?) coll)
         [:div.edition
          (if edit?
            [:span {:on-click on-save} i/save]
            [:span {:on-click on-edit} i/pencil])
          [:span {:on-click on-delete} i/trash]])])))

;; --- Nav

(mx/defc nav-item
  {:mixins [mx/static]}
  [collection selected?]
  (letfn [(on-click [event]
            (let [type (:type collection)
                  id (:id collection)]
              (rs/emit! (di/select-collection type id))))]
    (let [icons (count (:icons collection))]
      [:li {:on-click on-click
            :class-name (when selected? "current")}
       [:span.element-title (:name collection)]
       [:span.element-subtitle
        (tr "ds.num-elements" (t/c icons))]])))

(mx/defc nav-section
  {:mixins [mx/static mx/reactive]}
  [type selected]
  (let [own? (= type :own)
        builtin? (= type :builtin)
        collections (cond->> (mx/react collections-ref)
                      own? (filter #(= :own (:type %)))
                      builtin? (filter #(= :builtin (:type %)))
                      own? (sort-by :id))]
    [:ul.library-elements
     (when own?
       [:li
        [:a.btn-primary
         #_{:on-click #(rs/emit! (di/create-collection))}
         "+ New library"]])
     (for [coll collections
           :let [selected? (= (:id coll) selected)
                 key (str (:id coll))]]
       (-> (nav-item coll selected?)
           (mx/with-key key)))]))

(mx/defc nav
  {:mixins [mx/static]}
  [state]
  (let [selected (:id state)
        type (:type state)
        own? (= type :own)
        builtin? (= type :builtin)]
    (letfn [(select-tab [type]
              (let [xf (filter #(= type (:type %)))
                    colls (sequence xf @collections-ref)]
                (if-let [item (first colls)]
                  (rs/emit! (di/select-collection type (:id item)))
                  (rs/emit! (di/select-collection type)))))]
      [:div.library-bar
       [:div.library-bar-inside
        [:ul.library-tabs
         [:li {:class-name (when builtin? "current")
               :on-click (partial select-tab :builtin)}
          "STANDARD"]
         [:li {:class-name (when own? "current")
               :on-click (partial select-tab :own)}
          "YOUR LIBRARIES"]]
        (nav-section type selected)]])))

;; --- Grid

;; (defn grid-render
;;   [own]
;;   (let [dashboard (mx/react dashboard-ref)
;;         coll-type (:collection-type dashboard)
;;         coll-id (:collection-id dashboard)
;;         own? (= coll-type :own)
;;         coll (get library/+icon-collections-by-id+ coll-id)]
;;     (when coll
;;       (html
;;        [:section.dashboard-grid.library
;;         (page-title coll)
;;         [:div.dashboard-grid-content
;;           [:div.dashboard-grid-row
;;            (for [icon (:icons coll)]
;;              [:div.grid-item.small-item.project-th {}
;;               [:span.grid-item-icon (icon/icon-svg icon)]
;;               [:h3 (:name icon)]
;;               #_[:div.project-th-actions
;;                [:div.project-th-icon.edit i/pencil]
;;                [:div.project-th-icon.delete i/trash]]])]]]))))

;; (def grid
;;   (mx/component
;;    {:render grid-render
;;     :name "grid"
;;     :mixins [mx/static mx/reactive]}))

(mx/defc grid-options
  [coll]
  (let [own? (= (:type coll) :own)]
    (letfn [(delete []
              #_(rs/emit! (di/delete-selected)))
            (on-delete [event]
              (udl/open! :confirm {:on-accept delete}))]
      ;; MULTISELECT OPTIONS BAR
      [:div.multiselect-bar
       (if own?
         [:div.multiselect-nav
          #_[:span.move-item.tooltip.tooltip-top
             {:alt "Move to"}
             i/organize]
          [:span.delete.tooltip.tooltip-top
           {:alt "Delete"
            :on-click on-delete}
           i/trash]]
         [:div.multiselect-nav
          [:span.move-item.tooltip.tooltip-top
           {:alt "Copy to"}
           i/organize]])])))

(mx/defc grid-item
  [{:keys [id] :as icon} selected?]
  (letfn [(toggle-selection [event]
            (rs/emit! (di/toggle-icon-selection id)))
          (toggle-selection-shifted [event]
            (when (kbd/shift? event)
              (toggle-selection event)))]
    [:div.grid-item.small-item.project-th
     {:on-click toggle-selection-shifted}
     [:span.grid-item-image (icon/icon-svg icon)]
     [:h3 (:name icon)]
     #_[:div.project-th-actions
        [:div.project-th-icon.edit i/pencil]
        [:div.project-th-icon.delete i/trash]]]))

(mx/defc grid
  {:mixins [mx/static]}
  [state selected {:keys [id type icons] :as coll}]
  (let [own? (= type :own)
        ordering (:order state)
        filtering (:filter state)
        icons (->> icons
                    (remove nil?)
                    (filter-icons-by filtering)
                    (sort-icons-by ordering))]
    [:div.dashboard-grid-content
     [:div.dashboard-grid-row
      #_(when own?
        (grid-form id))
      (for [icon icons
            :let [id (:id icon)
                  selected? (contains? selected id)]]
        (-> (grid-item icon selected?)
            (mx/with-key (str id))))]]))

(mx/defc content
  {:mixins [mx/static]}
  [state coll]
  (let [selected (:selected state)
        coll-type (:type coll)
        own? (= coll-type :own)]
    (when coll
      [:section.dashboard-grid.library
       (page-title coll)
       (grid state selected coll)
       (when (seq selected)
         (grid-options coll))])))

;; --- Menu

(mx/defc menu
  {:mixins [mx/static]}
  [state coll]
  (let [ordering (:order state :name)
        filtering (:filter state "")
        icount (count (:icons coll))]
    (letfn [(on-term-change [event]
              (let [term (-> (dom/get-target event)
                             (dom/get-value))]
                (rs/emit! (di/update-opts :filter term))))
            (on-ordering-change [event]
              (let [value (dom/event->value event)
                    value (read-string value)]
                (rs/emit! (di/update-opts :order value))))
            (on-clear [event]
              (rs/emit! (di/update-opts :filter "")))]
      [:section.dashboard-bar.library-gap
       [:div.dashboard-info

        ;; Counter
        [:span.dashboard-icons (tr "ds.num-icons" (t/c icount))]

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
          {:key :icons-search-box
           :type "text"
           :on-change on-term-change
           :auto-focus true
           :placeholder (tr "ds.project-search.placeholder")
           :value (or filtering "")}]
         [:div.clear-search {:on-click on-clear} i/close]]]])))

;; --- Icons Page

(defn- icons-page-will-mount
  [own]
  (let [[type id] (:rum/args own)]
    (rs/emit! (di/initialize type id))
    own))

(defn- icons-page-did-remount
  [old-own own]
  (let [[old-type old-id] (:rum/args old-own)
        [new-type new-id] (:rum/args own)]
    (when (or (not= old-type new-type)
              (not= old-id new-id))
      (rs/emit! (di/initialize new-type new-id)))
    own))

(mx/defc icons-page
  {:will-mount icons-page-will-mount
   :did-remount icons-page-did-remount
   :mixins [mx/static mx/reactive]}
  []
  (let [state (mx/react dashboard-ref)
        coll-id (:id state)
        coll (mx/react (focus-collection coll-id))]
    [:main.dashboard-main
     (header)
     [:section.dashboard-content
      (nav state)
      (menu state coll)
      (content state coll)]]))

;; --- New Icon Lightbox (TODO)

(defn- new-icon-lightbox-render
  [own]
  (html
   [:div.lightbox-body
    [:h3 "New icon"]
    [:div.row-flex
     [:div.lightbox-big-btn
      [:span.big-svg i/shapes]
      [:span.text "Go to workspace"]
      ]
     [:div.lightbox-big-btn
      [:span.big-svg.upload i/exit]
      [:span.text "Upload file"]
      ]
     ]
    [:a.close {:href "#"
               :on-click #(do (dom/prevent-default %)
                              (udl/close!))}
     i/close]]))

(def new-icon-lightbox
  (mx/component
   {:render new-icon-lightbox-render
    :name "new-icon-lightbox"}))

(defmethod lbx/render-lightbox :new-icon
  [_]
  (new-icon-lightbox))
