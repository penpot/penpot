;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.dashboard.colors
  (:require [cuerdas.core :as str]
            [lentes.core :as l]
            [potok.core :as ptk]
            [uxbox.main.data.colors :as dc]
            [uxbox.main.data.dashboard :as dd]
            [uxbox.main.data.lightbox :as udl]
            [uxbox.main.store :as st]
            [uxbox.main.ui.messages :refer [messages-widget]]
            [uxbox.main.ui.colorpicker :refer (colorpicker)]
            [uxbox.main.ui.dashboard.header :refer (header)]
            [uxbox.builtins.icons :as i]
            [uxbox.main.ui.keyboard :as k]
            [uxbox.main.ui.lightbox :as lbx]
            [uxbox.util.color :refer (hex->rgb)]
            [uxbox.util.dom :as dom]
            [uxbox.util.i18n :as t :refer (tr)]
            [uxbox.util.lens :as ul]
            [rumext.core :as mx :include-macros true]))

;; --- Refs

(def dashboard-ref
  (-> (l/in [:dashboard :colors])
      (l/derive st/state)))

(def collections-ref
  (-> (l/key :colors-collections)
      (l/derive st/state)))

;; --- Page Title

(mx/defcs page-title
  {:mixins [(mx/local {}) mx/static mx/reactive]}
  [own {:keys [id] :as coll}]
  (let [local (:rum/local own)
        dashboard (mx/react dashboard-ref)
        own? (= :own (:type coll))
        edit? (:edit @local)]
    (letfn [(save []
              (let [dom (mx/ref-node own "input")
                    name (dom/get-inner-text dom)]
                (st/emit! (dc/rename-collection id (str/trim name)))
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
            (delete []
              (st/emit! (dc/delete-collection id)))
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
           [:span.close {:on-click cancel} i/close]]
          (if own?
            [:span.dashboard-title-field
             {:on-double-click edit}
             (:name coll)]
            [:span.dashboard-title-field
             (:name coll)]))]
       (when (and own? coll)
         [:div.edition
          (if edit?
            [:span {:on-click save} i/save]
            [:span {:on-click edit} i/pencil])
          [:span {:on-click on-delete} i/trash]])])))

;; --- Nav

(mx/defcs nav-item
  {:mixins [(mx/local) mx/static]}
  [own {:keys [id type name] :as coll} selected?]
  (let [colors (count (:colors coll))
        editable? (= type :own)
        local (:rum/local own)]
    (letfn [(on-click [event]
              (let [type (or type :own)]
                (st/emit! (dc/select-collection type id))))
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
              (when (k/enter? event)
                (let [value (dom/get-target event)
                      value (dom/get-value value)]
                  (st/emit! (dc/rename-collection id (str/trim (:name @local))))
                  (swap! local assoc :edit false))))]
      [:li {:on-click on-click
            :on-double-click on-double-click
            :class-name (when selected? "current")}
       (if (:edit @local)
         [:div
          [:input.element-title
            {:value (if (:name @local) (:name @local) name)
             :on-change on-input-change
             :on-key-down on-input-keyup}]
          [:span.close {:on-click on-cancel} i/close]]
         [:span.element-title name])
       [:span.element-subtitle
        (tr "ds.num-elements" (t/c colors))]])))

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
         {:on-click #(st/emit! (dc/create-collection))}
         "+ New library"]])
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
                (st/emit! (dc/select-collection type))
                (let [coll (->> (map second colls)
                                 (filter #(= type (:type %)))
                                 (sort-by :created-at)
                                 (first))]
                  (if coll
                    (st/emit! (dc/select-collection type (:id coll)))
                    (st/emit! (dc/select-collection type))))))]
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
              (st/emit! (dc/delete-selected-colors)))
            (on-delete [event]
              (udl/open! :confirm {:on-accept delete}))
            (on-toggle-copy [event]
              (swap! local update :show-copy-tooltip not)
              (swap! local assoc :show-move-tooltip false))
            (on-toggle-move [event]
              (swap! local update :show-move-tooltip not)
              (swap! local assoc :show-copy-tooltip false))
            (on-copy [selected]
              (swap! local assoc
                     :show-move-tooltip false
                     :show-copy-tooltip false)
              (st/emit! (dc/copy-selected selected)))
            (on-move [selected]
              (swap! local assoc
                     :show-move-tooltip false
                     :show-copy-tooltip false)
              (st/emit! (dc/move-selected id selected)))]

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
  [color selected?]
  (let [color-rgb (hex->rgb color)]
    (letfn [(toggle-selection [event]
              (st/emit! (dc/toggle-color-selection color)))]
      [:div.grid-item.small-item.project-th
       {:on-click toggle-selection}
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
    (st/emit! (dc/initialize type id))
    own))

(defn- colors-page-did-remount
  [old-own own]
  (let [[old-type old-id] (:rum/args old-own)
        [new-type new-id] (:rum/args own)]
    (when (or (not= old-type new-type)
              (not= old-id new-id))
      (st/emit! (dc/initialize new-type new-id)))
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
     (messages-widget)
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
                (st/emit! (dc/replace-color params))
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
