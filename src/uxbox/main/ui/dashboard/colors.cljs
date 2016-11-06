;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.dashboard.colors
  (:require [cuerdas.core :as str]
            [lentes.core :as l]
            [uxbox.main.data.colors :as dc]
            [uxbox.main.data.dashboard :as dd]
            [uxbox.main.data.lightbox :as udl]
            [uxbox.main.state :as st]
            [uxbox.main.ui.colorpicker :refer (colorpicker)]
            [uxbox.main.ui.dashboard.header :refer (header)]
            [uxbox.main.ui.forms :as form]
            [uxbox.main.ui.icons :as i]
            [uxbox.main.ui.keyboard :as k]
            [uxbox.main.ui.lightbox :as lbx]
            [uxbox.util.color :refer (hex->rgb)]
            [uxbox.util.dom :as dom]
            [uxbox.util.i18n :as t :refer (tr)]
            [uxbox.util.lens :as ul]
            [uxbox.util.mixins :as mx :include-macros true]
            [uxbox.util.rstore :as rs]
            [uxbox.util.schema :as sc]))

;; --- Refs

(def dashboard-ref
  (-> (l/in [:dashboard :colors])
      (l/derive st/state)))

(def collections-ref
  (-> (l/key :color-collections)
      (l/derive st/state)))

;; --- Page Title

(mx/defcs page-title
  {:mixins [(mx/local {}) mx/static mx/reactive]}
  [own {:keys [id] :as coll}]
  (let [local (:rum/local own)
        dashboard (mx/react dashboard-ref)
        own? (= :own (:type coll))
        editable? (or own? (nil? id))
        edit? (:edit @local)]
    (letfn [(save []
              (let [dom (mx/ref-node own "input")
                    name (dom/get-inner-text dom)]
                (rs/emit! (dc/rename-collection id (str/trim name)))
                (swap! local assoc :edit false)))
            (cancel []
              (swap! local assoc :edit false))
            (edit []
              (swap! local assoc :edit true))
            (on-input-keydown [e]
              (cond
                (k/esc? e) (cancel)
                (k/enter? e)
                (do
                  (dom/prevent-default e)
                  (dom/stop-propagation e)
                  (save))))
            (delete-collection []
              (rs/emit! (dc/delete-collection (:id coll))))]
      [:div.dashboard-title
       [:h2
        (if edit?
          [:div.dashboard-title-field
           [:span.edit
            {:content-editable true
             :ref "input"
             :on-key-down on-input-keydown}
            (:name coll)]
           [:span.close {:on-click cancel} i/close]]
          (if own?
            [:span.dashboard-title-field
             {:on-double-click edit}
             (:name coll)]
            [:span.dashboard-title-field
             (:name coll "Storage")]))]
       (if (and own? coll)
         [:div.edition
          (if edit?
            [:span {:on-click save} i/save]
            [:span {:on-click edit} i/pencil])
          [:span {:on-click delete-collection} i/trash]])])))

;; --- Nav

(mx/defc nav-item
  {:mixins [mx/static]}
  [{:keys [id type name] :as coll} selected?]
  (letfn [(on-click [event]
            (let [type (or type :own)]
              (rs/emit! (dc/select-collection type id))))]
    (let [colors (count (:colors coll))]
      [:li {:on-click on-click
            :class-name (when selected? "current")}
       [:span.element-title
        (if coll name "Storage")]
       [:span.element-subtitle
        (tr "ds.num-elements" (t/c colors))]])))

(def ^:private storage-num-colors-ref
  (-> (comp (l/in [:color-collections nil :colors])
            (l/lens count))
      (l/derive st/state)))

(mx/defc nav-item-storage
  {:mixins [mx/static mx/reactive]}
  [selected?]
  (let [num-colors (mx/react storage-num-colors-ref)
        on-click #(rs/emit! (dc/select-collection :own nil))]
    [:li {:on-click on-click :class (when selected? "current")}
     [:span.element-title "Storage"]
     [:span.element-subtitle
      (tr "ds.num-elements" (t/c num-colors))]]))

(mx/defc nav-section
  {:mixins [mx/static]}
  [type selected colls]
  (let [own? (= type :own)
        builtin? (= type :builtin)
        colls (cond->> (vals colls)
                own? (filter #(= :own (:type %)))
                builtin? (filter #(= :builtin (:type %)))
                own? (sort-by :created-at)
                builtin? (sort-by :created-at))]
    [:ul.library-elements
     (when own?
       [:li
        [:a.btn-primary
         {:on-click #(rs/emit! (dc/create-collection))}
         "+ New library"]])
     (when own?
       (nav-item-storage (nil? selected)))
     (for [coll colls
           :let [selected? (= (:id coll) selected)
                 key (str (:id coll))]]
       (-> (nav-item coll selected?)
           (mx/with-key key)))]))

(mx/defc nav
  {:mixins [mx/static mx/reactive]}
  [{:keys [id type] :as state} colls]
  (let [own? (= type :own)
        builtin? (= type :builtin)]
    (letfn [(select-tab [type]
              (if (= type :own)
                (rs/emit! (dc/select-collection type))
                (let [coll (->> (map second colls)
                                 (filter #(= type (:type %)))
                                 (sort-by :created-at)
                                 (first))]
                  (if coll
                    (rs/emit! (dc/select-collection type (:id coll)))
                    (rs/emit! (dc/select-collection type))))))]
      [:div.library-bar
       [:div.library-bar-inside
        [:ul.library-tabs
         [:li {:class-name (when own? "current")
               :on-click (partial select-tab :own)}
          "YOUR COLORS"]
         [:li {:class-name (when builtin? "current")
               :on-click (partial select-tab :builtin)}
          "COLORS STORE"]]
        (nav-section type id colls)]])))

;; --- Grid

(mx/defc grid-form
  [coll-id]
  [:div.grid-item.small-item.add-project
   {:on-click #(udl/open! :color-form {:coll coll-id})}
   [:span "+ New color"]])

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
                    (rs/emit! (dc/copy-selected id)))]
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
                    (dom/prevent-default event)
                    (rs/emit! (dc/move-selected current-coll id)))]
    [:ul.move-list
     [:li.title "Move to library"]
     [:li [:a {:href "#" :on-click #(on-select % nil)} "Storage"]]
     (for [coll colls
           :let [id (:id coll)
                 name (:name coll)]]
       [:li {:key (str id)}
        [:a {:on-click #(on-select % id)} name]])]))

(mx/defcs grid-options
  {:mixins [mx/static (mx/local)]}
  [own {:keys [type id] :as coll}]
  (let [editable? (or (= type :own) (nil? id))
        local (:rum/local own)]
    (letfn [(delete [event]
              (rs/emit! (dc/delete-selected-colors)))
            (on-delete [event]
              (udl/open! :confirm {:on-accept delete}))
            (on-toggle-copy [event]
              (swap! local assoc
                     :show-copy-tooltip not
                     :show-move-tooltip false))
            (on-toggle-move [event]
              (swap! local assoc
                     :show-move-tooltip not
                     :show-copy-tooltip false))]
      ;; MULTISELECT OPTIONS BAR
      [:div.multiselect-bar
       (if editable?
         [:div.multiselect-nav
          [:span.move-item.tooltip.tooltip-top
           {:on-click on-toggle-copy}
           (when (:show-copy-tooltip @local)
             (grid-options-copy id))
           i/organize]
          [:span.move-item.tooltip.tooltip-top
           {:on-click on-toggle-move}
           (when (:show-move-tooltip @local)
             (grid-options-move id))
           i/organize]
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
  [color selected?]
  (let [color-rgb (hex->rgb color)]
    (letfn [(toggle-selection [event]
              (rs/emit! (dc/toggle-color-selection color)))
            (toggle-selection-shifted [event]
              (when (k/shift? event)
                (toggle-selection event)))]
      [:div.grid-item.small-item.project-th
       {:on-click toggle-selection-shifted}
       [:span.color-swatch {:style {:background-color color}}]
       [:div.input-checkbox.check-primary
        [:input {:type "checkbox"
                 :id color
                 :on-click toggle-selection
                 :checked selected?}]
        [:label {:for color}]]
       [:span.color-data color]
       [:span.color-data (apply str "RGB " (interpose ", " color-rgb))]])))

(mx/defc grid
  {:mixins [mx/static]}
  [{:keys [id type colors] :as coll} selected]
  (let [editable? (or (= :own type) (nil? id))
        colors (->> (remove nil? colors)
                    (sort-by identity))]
    [:div.dashboard-grid-content
     [:div.dashboard-grid-row
      (when editable? (grid-form id))
      (for [color colors
            :let [selected? (contains? selected color)]]
        (-> (grid-item color selected?)
            (mx/with-key (str color))))]]))

(mx/defc content
  {:mixins [mx/static]}
  [{:keys [selected] :as state} coll]
  [:section.dashboard-grid.library
   (page-title coll)
   (grid coll selected)
   (when (and (seq selected))
     (grid-options coll))])

;; --- Colors Page

(defn- colors-page-will-mount
  [own]
  (let [[type id] (:rum/args own)]
    (rs/emit! (dc/initialize type id))
    own))

(defn- colors-page-did-remount
  [old-own own]
  (let [[old-type old-id] (:rum/args old-own)
        [new-type new-id] (:rum/args own)]
    (when (or (not= old-type new-type)
              (not= old-id new-id))
      (rs/emit! (dc/initialize new-type new-id)))
    own))

(mx/defc colors-page
  {:will-mount colors-page-will-mount
   :did-remount colors-page-did-remount
   :mixins [mx/static mx/reactive]}
  [_ _]
  (let [state (mx/react dashboard-ref)
        colls (mx/react collections-ref)
        coll (get colls (:id state))]
    [:main.dashboard-main
     (header)
     [:section.dashboard-content
      (nav state colls)
      (content state coll)]]))

;; --- Colors Lightbox (Component)

(mx/defcs color-lightbox
  {:mixins [(mx/local {}) mx/static]}
  [own {:keys [coll color] :as params}]
  (let [local (:rum/local own)]
    (letfn [(on-submit [event]
              (let [params {:id coll
                            :from color
                            :to (:hex @local)}]
                (rs/emit! (dc/replace-color params))
                (udl/close!)))
            (on-change [event]
              (let [value (str/trim (dom/event->value event))]
                (swap! local assoc :hex value)))
            (on-close [event]
              (udl/close!))]
      [:div.lightbox-body
       [:h3 "New color"]
       [:form
        [:div.row-flex.center
         (colorpicker
          :value (or (:hex @local) color "#00ccff")
          :on-change #(swap! local assoc :hex %))]

        [:input#project-btn.btn-primary
         {:value "+ Add color"
          :on-click on-submit
          :type "button"}]]
       [:a.close {:on-click on-close} i/close]])))

(defmethod lbx/render-lightbox :color-form
  [params]
  (color-lightbox params))
