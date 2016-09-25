;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.dashboard.images
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [cuerdas.core :as str]
            [lentes.core :as l]
            [uxbox.util.i18n :as t :refer (tr)]
            [uxbox.main.state :as st]
            [uxbox.util.rstore :as rs]
            [uxbox.main.library :as library]
            [uxbox.main.data.dashboard :as dd]
            [uxbox.main.data.lightbox :as udl]
            [uxbox.main.data.images :as di]
            [uxbox.main.ui.icons :as i]
            [uxbox.util.mixins :as mx]
            [uxbox.main.ui.lightbox :as lbx]
            [uxbox.main.ui.keyboard :as kbd]
            [uxbox.main.ui.library-bar :as ui.library-bar]
            [uxbox.main.ui.dashboard.header :refer (header)]
            [uxbox.util.data :as data]
            [uxbox.util.lens :as ul]
            [uxbox.util.dom :as dom]))

;; --- Helpers & Constants

(def +ordering-options+
  {:name "ds.project-ordering.by-name"
   :created "ds.project-ordering.by-creation-date"})

(defn- sort-images-by
  [ordering projs]
  (case ordering
    :name (sort-by :name projs)
    :created (reverse (sort-by :created-at projs))
    projs))

(defn- contains-term?
  [phrase term]
  (str/includes? (str/lower phrase) (str/trim (str/lower term))))

(defn- filter-images-by
  [term projs]
  (if (str/blank? term)
    projs
    (filter #(contains-term? (:name %) term) projs)))

;; --- Refs

(def ^:private dashboard-ref
  (-> (l/key :dashboard)
      (l/derive st/state)))

;; (def ^:private collections-by-id-ref
;;   (-> (l/key :images-by-id)
;;       (l/derive st/state)))

;; (def ^:private images-ordering-ref
;;   (-> (l/in [:dashboard :images-order])
;;       (l/derive st/state)))

;; (def ^:private images-filtering-ref
;;   (-> (l/in [:dashboard :images-filter])
;;       (l/derive st/state)))

(def ^:private collections-map-ref
  (-> (comp (l/key :images-by-id)
            (ul/merge library/+image-collections-by-id+))
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
                (rs/emit! (di/rename-collection id (str/trim name)))
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
            (on-delete []
              (rs/emit! (di/delete-collection (:id coll))))]
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
    (let [colors (count (:data collection))]
      [:li {:on-click on-click
            :class-name (when selected? "current")}
       [:span.element-title (:name collection)]
       [:span.element-subtitle
        (tr "ds.num-elements" (t/c colors))]])))

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
         {:on-click #(rs/emit! (di/create-collection))}
         "+ New library"]])
     (for [coll collections
           :let [selected? (= (:id coll) selected)
                 key (str (:id coll))]]
       (-> (nav-item coll selected?)
           (mx/with-key key)))]))

(mx/defc nav
  {:mixins [mx/static mx/reactive]}
  []
  (let [dashboard (mx/react dashboard-ref)
        collections (mx/react collections-ref)
        selected (:id dashboard)
        type (:type dashboard)
        own? (= type :own)
        builtin? (= type :builtin)]
    (letfn [(select-tab [type]
              (let [xf (filter #(= type (:type %)))
                    colls (sequence xf collections)]
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

;; (defn- grid-render
;;   [own]
;;   (let [local (:rum/local own)
;;         dashboard (mx/react dashboard-ref)
;;         coll-type (:collection-type dashboard)
;;         coll-id (:collection-id dashboard)
;;         own? (= coll-type :own)
;;         builtin? (= coll-type :builtin)
;;         coll (if builtin?
;;                (get library/+image-collections-by-id+ coll-id)
;;                (mx/react (focus-collection coll-id)))
;;         images-filtering (mx/react images-filtering-ref)
;;         images-ordering (mx/react images-ordering-ref)
;;         images (->> (:images coll)
;;                     (remove nil?)
;;                     (filter-images-by images-filtering)
;;                     (sort-images-by images-ordering))
;;         show-menu? (not (empty? (:selected @local)))]
;;     (letfn [(toggle-check [image]
;;               (swap! local update :selected #(data/conj-or-disj % image)))
;;             (toggle-check-card [image event]
;;               (when (kbd/shift? event)
;;                 (toggle-check image)))
;;             (delete-selected []
;;               (->> (:selected @local)
;;                    (run! #(rs/emit! (di/delete-image coll-id %)))))
;;       (when coll
;;         (html
;;          [:section.dashboard-grid.library
;;           (page-title coll)
;;           [:div.dashboard-grid-content
;;            [:div.dashboard-grid-row
;;             (if own?
;;               [:div.grid-item.add-project {:on-click forward-click}
;;                [:span "+ New image"]
;;                [:input.upload-image-input
;;                 {:style {:display "none"}
;;                  :multiple true
;;                  :ref "file-input"
;;                  :value ""
;;                  :type "file"
;;                  :on-change on-file-selected}]])

;;             (for [image images
;;                   :let [selected? (contains? (:selected @local) image)]]
;;               [:div.grid-item.images-th
;;                {:key (:id image)
;;                 :on-click (partial toggle-check-card image)}
;;                [:div.grid-item-th
;;                 {:style {:background-image (str "url('" (:thumbnail image) "')")}}
;;                 [:div.input-checkbox.check-primary
;;                  [:input {:type "checkbox"
;;                           :id (:id image)
;;                           :on-click (partial toggle-check image)
;;                           :checked selected?}]
;;                  [:label {:for (:id image)}]]]
;;                [:span (:name image)]])]

;;            (when show-menu?
;;              ;; MULTISELECT OPTIONS BAR
;;              [:div.multiselect-bar
;;               [:div.multiselect-nav
;;                [:span.move-item.tooltip.tooltip-top {:alt "Copy to"} i/organize]
;;                (if own?
;;                  [:span.copy.tooltip.tooltip-top {:alt "Duplicate"} i/copy])
;;                (if own?
;;                  [:span.delete.tooltip.tooltip-top
;;                   {:alt "Delete"
;;                    :on-click delete-selected}
;;                   i/trash])]])]])))))

;; (def ^:private grid
;;   (mx/component
;;    {:render grid-render
;;     :name "grid"
;;     :mixins [(rum/local {:selected #{}}) mx/static mx/reactive]}))

(mx/defcs grid-form
  {:mixins [mx/static]}
  [own coll-id]
  (letfn [(forward-click [event]
            (dom/click (mx/ref-node own "file-input")))
          (on-file-selected [event]
            (let [files (dom/get-event-files event)]
              (rs/emit! (di/create-images coll-id files))))]
    [:div.grid-item.add-project {:on-click forward-click}
     [:span "+ New image"]
     [:input.upload-image-input
      {:style {:display "none"}
       :multiple true
       :ref "file-input"
       :value ""
       :type "file"
       :on-change on-file-selected}]]))

(mx/defc grid-options
  [coll]
  (let [own? (= (:type coll) :own)]
    (letfn [(on-delete [event]
              (rs/emit! (di/delete-selected)))]
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
  [{:keys [id] :as image} selected?]
  (letfn [(toggle-selection [event]
            (rs/emit! (di/toggle-image-selection id)))
          (toggle-selection-shifted [event]
            (when (kbd/shift? event)
              (toggle-selection event)))]
    [:div.grid-item.images-th
     {:on-click toggle-selection-shifted}
     [:div.grid-item-th
      {:style {:background-image (str "url('" (:thumbnail image) "')")}}
      [:div.input-checkbox.check-primary
       [:input {:type "checkbox"
                :id (:id image)
                :on-click toggle-selection
                :checked selected?}]
       [:label {:for (:id image)}]]]
     [:span (:name image)]]))

(mx/defc grid
  {:mixins [mx/static]}
  [selected {:keys [id type images] :as coll}]
  (let [own? (= type :own)]
    (println (map :id images))
    [:div.dashboard-grid-content
     [:div.dashboard-grid-row
      (when own?
        (grid-form id))
      (for [image images
            :let [id (:id image)
                  selected? (contains? selected id)]]
        (-> (grid-item image selected?)
            (mx/with-key (str id))))]]))

(mx/defc content
  {:mixins [mx/static mx/reactive]}
  []
  (let [dashboard (mx/react dashboard-ref)
        selected (:selected dashboard)
        coll-type (:type dashboard)
        coll-id (:id dashboard)
        coll (mx/react (focus-collection coll-id))
        own? (= coll-type :own)]
    (when coll
      [:section.dashboard-grid.library
       (page-title coll)
       (grid selected coll)
       (when (and (seq selected))
         (grid-options coll))])))

;; --- Sort Widget

;; (defn- sort-widget-render
;;   []
;;   (let [ordering (mx/react images-ordering-ref)
;;         on-change #(rs/emit! (di/set-images-ordering
;;                               (keyword (.-value (.-target %)))))]
;;     (html
;;      [:div
;;       [:span (tr "ds.project-ordering")]
;;       [:select.input-select
;;        {:on-change on-change
;;         :value (:name ordering "")}
;;        (for [option (keys +ordering-options+)
;;              :let [option-id (get +ordering-options+ option)
;;                    option-value (:name option)
;;                    option-text (tr option-id)]]
;;          [:option
;;           {:key option-id
;;            :value option-value}
;;           option-text])]])))

;; (def ^:private sort-widget
;;   (mx/component
;;    {:render sort-widget-render
;;     :name "sort-widget-render"
;;     :mixins [mx/reactive mx/static]}))

;; --- Filtering Widget

;; (defn- search-widget-render
;;   []
;;   (letfn [(on-term-change [event]
;;             (-> (dom/get-target event)
;;                 (dom/get-value)
;;                 (di/set-images-filtering)
;;                 (rs/emit!)))
;;           (on-clear [event]
;;             (rs/emit! (di/clear-images-filtering)))]
;;     (html
;;      [:form.dashboard-search
;;       [:input.input-text
;;        {:key :images-search-box
;;         :type "text"
;;         :on-change on-term-change
;;         :auto-focus true
;;         :placeholder (tr "ds.project-search.placeholder")
;;         :value (mx/react images-filtering-ref)}]
;;       [:div.clear-search {:on-click on-clear} i/close]])))

;; (def ^:private search-widget
;;   (mx/component
;;    {:render search-widget-render
;;     :name "search-widget"
;;     :mixins [mx/reactive mx/static]}))

;; ;; --- Menu

;; (defn- menu-render
;;   []
;;   (let [dashboard (mx/react dashboard-ref)
;;         coll-id (:collection-id dashboard)
;;         coll (mx/react (focus-collection coll-id))
;;         icount (count (:images coll)) ]
;;     (html
;;      [:section.dashboard-bar.library-gap
;;       [:div.dashboard-info
;;        [:span.dashboard-images (tr "ds.num-images" (t/c icount))]
;;        (sort-widget)
;;        (search-widget)]])))

;; (def ^:private menu
;;   (mx/component
;;    {:render menu-render
;;     :name "menu"
;;     :mixins [mx/reactive mx/static]}))


;; --- Images Page

;; (defn- images-page-render
;;   [own]
;;   (html
;;    [:main.dashboard-main
;;     (header)
;;     [:section.dashboard-content
;;      (nav)
;;      (menu)
;;      (grid)]]))

(defn- images-page-will-mount
  [own]
  (let [[type id] (:rum/args own)]
    (rs/emit! (di/initialize type id))
    own))

(defn- images-page-did-remount
  [old-own own]
  (let [[old-type old-id] (:rum/args old-own)
        [new-type new-id] (:rum/args own)]
    (when (or (not= old-type new-type)
              (not= old-id new-id))
      (rs/emit! (di/initialize new-type new-id)))
    own))

(mx/defc images-page
  {:will-mount images-page-will-mount
   :did-remount images-page-did-remount
   :mixins [mx/static]}
  [type id]
  [:main.dashboard-main
   (header)
   [:section.dashboard-content
    (nav)
    (content)]])
