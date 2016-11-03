;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.dashboard.icons
  (:require [lentes.core :as l]
            [cuerdas.core :as str]
            [uxbox.main.state :as st]
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

(def ^:private collections-ref
  (-> (l/key :icons-collections)
      (l/derive st/state)))

(def ^:private icons-ref
  (-> (l/key :icons)
      (l/derive st/state)))

;; (defn- focus-collection
;;   [id]
;;   (-> (l/key id)
;;       (l/derive collections-map-ref)))

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
            (delete []
              (rs/emit! (di/delete-collection id)))
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
           (:name coll "Storage")])]
       (if (and (not own?) coll)
         [:div.edition
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

(mx/defc nav-item
  {:mixins [mx/static mx/reactive]}
  [{:keys [id type name num-icons] :as coll} selected?]
  (letfn [(on-click [event]
            (let [type (or type :own)]
              (rs/emit! (di/select-collection type id))))]
    (let [num-icons (or num-icons (react-count-icons id))]
      [:li {:on-click on-click
            :class-name (when selected? "current")}
       [:span.element-title
        (if coll name "Storage")]
       [:span.element-subtitle
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
    [:ul.library-elements
     (when own?
       [:li
        [:a.btn-primary
         {:on-click #(rs/emit! (di/create-collection))}
         "+ New collection"]])
     (when own?
       (nav-item nil (nil? selected)))
     (for [coll colls
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
              (if (= type :builtin)
                (let [colls (->> (map second colls)
                                 (filter #(= :builtin (:type %)))
                                 (sort-by :name))]
                  (rs/emit! (di/select-collection type (:id (first colls)))))
                (rs/emit! (di/select-collection type))))]
      [:div.library-bar
       [:div.library-bar-inside
        [:ul.library-tabs
         [:li {:class-name (when own? "current")
               :on-click (partial select-tab :own)}
          "YOUR ICONS"]
         [:li {:class-name (when builtin? "current")
               :on-click (partial select-tab :builtin)}
          "ICONS STORE"]]

        (nav-section type id colls)]])))

;; --- Grid

(mx/defcs grid-form
  {:mixins [mx/static]}
  [own coll-id]
  (letfn [(forward-click [event]
            (dom/click (mx/ref-node own "file-input")))
          (on-file-selected [event]
            (let [files (dom/get-event-files event)]
              (rs/emit! (di/create-icons coll-id files))))]
    [:div.grid-item.small-item.add-project {:on-click forward-click}
     [:span "+ New icon"]
     [:input.upload-image-input
      {:style {:display "none"}
       :multiple true
       :ref "file-input"
       :value ""
       :accept "image/svg+xml"
       :type "file"
       :on-change on-file-selected}]]))

(mx/defc grid-options-copy
  {:mixins [mx/reactive mx/static]}
  [current-coll]
  {:pre [(uuid? current-coll)]}
  (let [colls (mx/react collections-ref)
        colls (->> (vals colls)
                   (filter #(= :own (:type %)))
                   (remove #(= current-coll (:id %)))
                   (sort-by :name colls))
        on-select (fn [event id]
                    (dom/prevent-default event)
                    (rs/emit! (di/copy-selected id)))]
    [:ul.move-list
     [:li.title "Copy to library"]
     [:li [:a {:href "#" :on-click #(on-select % nil)} "Storage"]]
     (for [coll colls
           :let [id (:id coll)
                 name (:name coll)]]
       [:li {:key (str id)}
        [:a {:on-click #(on-select % id)} name]])]))

(mx/defc grid-options-move
  {:mixins [mx/reactive mx/static]}
  [current-coll]
  {:pre [(uuid? current-coll)]}
  (let [colls (mx/react collections-ref)
        colls (->> (vals colls)
                   (filter #(= :own (:type %)))
                   (remove #(= current-coll (:id %)))
                   (sort-by :name colls))
        on-select (fn [event id]
                    (println "on-select" event id)
                    (dom/prevent-default event)
                    (rs/emit! (di/move-selected id)))]
    [:ul.move-list
     [:li.title "Move to library"]
     [:li [:a {:href "#" :on-click #(on-select % nil)} "Storage"]]
     (for [coll colls
           :let [id (:id coll)
                 name (:name coll)]]
       [:li {:key (str id)}
        [:a {:on-click #(on-select % id)} name]])]))

(mx/defcs grid-options
  {:mixins [(mx/local) mx/static]}
  [own {:keys [type id] :as coll}]
  (let [editable? (or (= type :own)
                      (nil? coll))
        local (:rum/local own)]
    (letfn [(delete []
              (rs/emit! (di/delete-selected)))
            (on-delete [event]
              (udl/open! :confirm {:on-accept delete}))
            (on-toggle-copy [event]
              (swap! local update :show-copy-tooltip not))
            (on-toggle-move [event]
              (swap! local update :show-move-tooltip not))]
      ;; MULTISELECT OPTIONS BAR
      [:div.multiselect-bar
       (if editable?
         [:div.multiselect-nav
          [:span.move-item.tooltip.tooltip-top
           {:alt "Move items" :on-click on-toggle-move}
           (when (:show-move-tooltip @local)
             (grid-options-move id))
           i/organize]
          [:span.move-item.tooltip.tooltip-top
           {:alt "Rename"}
           i/pencil]
          [:span.delete.tooltip.tooltip-top
           {:alt "Delete"
            :on-click on-delete}
           i/trash]]
         [:div.multiselect-nav
          [:span.move-item.tooltip.tooltip-top
           {:on-click on-toggle-copy}
           (when (:show-copy-tooltip @local)
             (grid-options-copy id))
           i/organize]])])))

(mx/defc grid-item
  [{:keys [id] :as icon} selected?]
  (letfn [(toggle-selection [event]
            (rs/emit! (di/toggle-icon-selection id)))
          (toggle-selection-shifted [event]
            (when (kbd/shift? event)
              (toggle-selection event)))]
    [:div.grid-item.small-item.project-th
     {:on-click toggle-selection-shifted
      :id (str "grid-item-" id)}
     [:div.input-checkbox.check-primary
      [:input {:type "checkbox"
               :id (:id icon)
               :on-click toggle-selection
               :checked selected?}]
      [:label {:for (:id icon)}]]
     [:span.grid-item-image
      (icon/icon-svg icon)]
     [:div.item-info
      [:h3 (:name icon)]
      [:span.date "Uploaded at 21/09/2016"]]
     #_[:div.project-th-actions
        [:div.project-th-icon.edit i/pencil]
        [:div.project-th-icon.delete i/trash]]]))

(mx/defc grid
  {:mixins [mx/static mx/reactive]}
  [{:keys [selected id type] :as state}]
  (let [editable? (or (= type :own) (nil? id))
        ordering (:order state)
        filtering (:filter state)
        icons (mx/react icons-ref)
        icons (->> (vals icons)
                   (filter #(= id (:collection %)))
                   (filter-icons-by filtering)
                   (sort-icons-by ordering))]
    [:div.dashboard-grid-content
     [:div.dashboard-grid-row
      (when editable? (grid-form id))
      (for [icon icons
            :let [id (:id icon)
                  selected? (contains? selected id)]]
        (-> (grid-item icon selected?)
            (mx/with-key (str id))))]]))

(mx/defc content
  {:mixins [mx/static]}
  [{:keys [selected] :as state} coll]
  [:section.dashboard-grid.library
   (page-title coll)
   (grid state)
   (when (seq selected)
     (grid-options coll))])

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
        [:span.dashboard-icons (tr "ds.num-icons" (t/c num-icons))]

        ;; Sorting
        [:divi
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
        colls (mx/react collections-ref)
        coll (get colls (:id state))]
    [:main.dashboard-main
     (header)
     [:section.dashboard-content
      (nav state colls)
      (menu state coll)
      (content state coll)]]))

;; --- New Icon Lightbox (TODO)

;; (defn- new-icon-lightbox-render
;;   [own]
;;   (html
;;    [:div.lightbox-body
;;     [:h3 "New icon"]
;;     [:div.row-flex
;;      [:div.lightbox-big-btn
;;       [:span.big-svg i/shapes]
;;       [:span.text "Go to workspace"]
;;       ]
;;      [:div.lightbox-big-btn
;;       [:span.big-svg.upload i/exit]
;;       [:span.text "Upload file"]
;;       ]
;;      ]
;;     [:a.close {:href "#"
;;                :on-click #(do (dom/prevent-default %)
;;                               (udl/close!))}
;;      i/close]]))

;; (def new-icon-lightbox
;;   (mx/component
;;    {:render new-icon-lightbox-render
;;     :name "new-icon-lightbox"}))

;; (defmethod lbx/render-lightbox :new-icon
;;   [_]
;;   (new-icon-lightbox))
