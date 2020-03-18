;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2015-2020 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2020 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.dashboard.library
  (:require
   [lentes.core :as l]
   [rumext.alpha :as mf]
   [cuerdas.core :as str]
   [uxbox.util.router :as rt]
   [uxbox.util.i18n :as i18n :refer [t tr]]
   [uxbox.util.color :as uc]
   [uxbox.util.dom :as dom]
   [uxbox.main.data.icons :as dico]
   [uxbox.main.data.images :as dimg]
   [uxbox.main.data.colors :as dcol]
   [uxbox.builtins.icons :as i]
   [uxbox.main.store :as st]
   [uxbox.main.refs :as refs]
   [uxbox.main.ui.components.context-menu :refer [context-menu]]
   [uxbox.main.ui.modal :as modal]
   [uxbox.main.ui.confirm :refer [confirm-dialog]]
   [uxbox.main.ui.colorpicker :refer [colorpicker most-used-colors]]
   ))

(mf/defc modal-create-color
  [{:keys [on-accept on-cancel] :as ctx}]
  (let [state (mf/use-state { :current-color "#406280" })]
    (letfn [(accept [event]
              (dom/prevent-default event)
              (modal/hide!)
              (when on-accept (on-accept (:current-color @state))))

            (cancel [event]
              (dom/prevent-default event)
              (modal/hide!)
              (when on-cancel (on-cancel)))]
      [:div.modal-create-color
       [:h3.modal-create-color-title (tr "modal.create-color.new-color")]
       [:& colorpicker {:value (:current-color @state)
                        :colors (into-array @most-used-colors)
                        :on-change #(swap! state assoc :current-color %)}]
     
       [:input.btn-primary {:type "button"
                            :value (tr "ds.button.save")
                            :on-click accept}]

       [:a.close {:href "#" :on-click cancel} i/close]])))


(defmulti create-library (fn [x _] x))
(defmethod create-library :icons [_ team-id]
  (let [name (str "Icon Library "(gensym "l"))]
    (st/emit! (dico/create-icon-library team-id name))))

(defmethod create-library :images [_ team-id]
  (let [name (str "Image Library "(gensym "l"))]
    (st/emit! (dimg/create-image-library team-id name))))

(defmethod create-library :palettes [_ team-id]
  (let [name (str "Image Library "(gensym "l"))]
    (st/emit! (dcol/create-color-library team-id name))))

(defmulti create-item (fn [x _ _] x))

(defmethod create-item :icons [_ library-id data]
  (let [files (->> data
                  (dom/get-target)
                  (dom/get-files)
                  (array-seq))]
    (st/emit! (dico/create-icons library-id files))))

(defmethod create-item :images [_ library-id data]
  (let [files (->> data
                  (dom/get-target)
                  (dom/get-files)
                  (array-seq))]
    (st/emit! (dimg/create-images library-id files))))

(defmethod create-item :palettes [_ library-id]
  (letfn [(dispatch-color [color]
            (st/emit! (dcol/create-color library-id color)))]
    (modal/show! modal-create-color {:on-accept dispatch-color})))

(mf/defc library-header
  [{:keys [section team-id] :as props}]
  (let [icons? (= section :icons)
        images? (= section :images)
        palettes? (= section :palettes)
        locale (i18n/use-locale)]
    [:header#main-bar.main-bar
     [:h1.dashboard-title "Libraries"]
     [:nav.library-header-navigation
      [:a.library-header-navigation-item
       {:class-name (when icons? "current")
        :on-click #(st/emit! (rt/nav :dashboard-library-icons-index {:team-id team-id}))}
       (t locale "dashboard.library.menu.icons")]
      [:a.library-header-navigation-item
       {:class-name (when images? "current")
        :on-click #(st/emit! (rt/nav :dashboard-library-images-index {:team-id team-id}))}
       (t locale "dashboard.library.menu.images")]
      [:a.library-header-navigation-item
       {:class-name (when palettes? "current")
        :on-click #(st/emit! (rt/nav :dashboard-library-palettes-index {:team-id team-id}))}
       (t locale "dashboard.library.menu.palettes")]]]))

(mf/defc library-sidebar
  [{:keys [section items team-id library-id]}]
  (let [locale (i18n/use-locale)]
    [:aside.library-sidebar
     [:button.library-sidebar-add-item
      {:type "button"
       :on-click #(create-library section team-id)}
      (t locale (str "dashboard.library.add-library." (name section)))]
     [:ul.library-sidebar-list
      (for [item items]
        [:li.library-sidebar-list-element
         {:key (:id item)
          :class-name (when (= library-id (:id item)) "current")
          :on-click
          (fn []
            (let [path (keyword (str "dashboard-library-" (name section)))]
              (dico/fetch-icon-library (:id item))
              (st/emit! (rt/nav path {:team-id team-id :library-id (:id item)}))))}
         [:a  (:name item)]])]]))

(mf/defc library-top-menu
  [{:keys [selected section library-id]}]
  (let [state (mf/use-state {:is-open false})
        locale (i18n/use-locale)]
    [:header.library-top-menu
     [:div.library-top-menu-current-element
      [:h2.library-top-menu-current-element-name (:name selected)]
      [:a.library-top-menu-current-action
       { :on-click #(swap! state update :is-open not)}
       [:span i/arrow-down]]
      [:& context-menu
       {:show (:is-open @state)
        :on-close #(swap! state update :is-open not)
        :options [[(t locale "ds.button.rename") #(println "Rename")]
                  [(t locale "ds.button.delete") #(println "Delete")]]}]]

     [:div.library-top-menu-actions
      [:a i/trash]

      (if (= section :palettes)
        [:button.btn-dashboard
         {:on-click #(create-item section library-id)}
         (t locale (str "dashboard.library.add-item." (name section)))]

        [:*
         [:label {:for "file-upload" :class-name "btn-dashboard"}
          (t locale (str "dashboard.library.add-item." (name section)))]
         [:input {:on-change #(create-item section library-id %)
                  :id "file-upload" :type "file" :style {:display "none"}}]]
       
        )]]))

(mf/defc library-icon-card
  [{:keys [id name url content metadata]}]
  (let [locale (i18n/use-locale)
        state (mf/use-state {:is-open false})]
    [:div.library-card.library-icon
     [:div.input-checkbox.check-primary
      [:input {:type "checkbox"
               :id (str "icon-" id)
               :on-change #(println "toggle-selection")
               #_(:checked false)}]
      [:label {:for (str "icon-" id)}]]
     [:div.library-card-image
      #_[:object { :data url :type "image/svg+xml" }]
      [:svg {:view-box (->> metadata :view-box (str/join " "))
             :width (:width metadata)
             :height (:height metadata) 
             :dangerouslySetInnerHTML {:__html content}}]]
     
     [:div.library-card-footer
      [:div.library-card-footer-name name]
      [:div.library-card-footer-timestamp "Less than 5 seconds ago"]
      [:div.library-card-footer-menu
       { :on-click #(swap! state update :is-open not) }
       i/actions]
      [:& context-menu
       {:show (:is-open @state)
        :on-close #(swap! state update :is-open not)
        :options [[(t locale "ds.button.delete") #(println "Delete")]]}]]]))

(mf/defc library-image-card
  [{:keys [id name thumb-uri]}]
  (let [locale (i18n/use-locale)
        state (mf/use-state {:is-open false})]
    [:div.library-card.library-image
     [:div.input-checkbox.check-primary
      [:input {:type "checkbox"
               :id (str "image-" id)
               :on-change #(println "toggle-selection")
               #_(:checked false)}]
      [:label {:for (str "image-" id)}]]
     [:div.library-card-image
      [:img {:src thumb-uri}]]
     [:div.library-card-footer
      [:div.library-card-footer-name name]
      [:div.library-card-footer-timestamp "Less than 5 seconds ago"]
      [:div.library-card-footer-menu
       { :on-click #(swap! state update :is-open not) }
       i/actions]
      [:& context-menu
       {:show (:is-open @state)
        :on-close #(swap! state update :is-open not)
        :options [[(t locale "ds.button.delete") #(println "Delete")]]}]]]))

(mf/defc library-color-card
  [{ :keys [ id content ] }]
  (when content
    (let [locale (i18n/use-locale)
         state (mf/use-state {:is-open false})]
     [:div.library-card.library-color
      [:div.input-checkbox.check-primary
       [:input {:type "checkbox"
                :id (str "color-" id)
                :on-change #(println "toggle-selection")
                #_(:checked false)}]
       [:label {:for (str "color-" id)}]]
      [:div.library-card-image
       { :style { :background-color content }}]
      [:div.library-card-footer
       [:div.library-card-footer-name content ]
       [:div.library-card-footer-color
        [:span.library-card-footer-color-label "RGB"]
        [:span.library-card-footer-color-rgb (str/join " " (uc/hex->rgb content))]]
       [:div.library-card-footer-menu
        { :on-click #(swap! state update :is-open not) }
        i/actions]
       [:& context-menu
        {:show (:is-open @state)
         :on-close #(swap! state update :is-open not)
         :options [[(t locale "ds.button.delete") #(println "Delete")]]}]]])))

(def icon-libraries-ref
  (-> (comp (l/key :library) (l/key :icon-libraries))
      (l/derive st/state)))

(def image-libraries-ref
  (-> (comp (l/key :library) (l/key :image-libraries))
      (l/derive st/state)))

(def color-libraries-ref
  (-> (comp (l/key :library) (l/key :color-libraries))
      (l/derive st/state)))

(def selected-items-ref
  (-> (comp (l/key :library) (l/key :selected-items))
      (l/derive st/state)))

(mf/defc library-page
  [{:keys [team-id library-id section]}]
  (mf/use-effect {:fn #(case section
                         :icons (st/emit! (dico/fetch-icon-libraries team-id))
                         :images (st/emit! (dimg/fetch-image-libraries team-id))
                         :palettes (st/emit! (dcol/fetch-color-libraries team-id)))
                  :deps (mf/deps section team-id)})
  (mf/use-effect {:fn #(when library-id
                         (case section
                           :icons (st/emit! (dico/fetch-icon-library library-id))
                           :images (st/emit! (dimg/fetch-image-library library-id))
                           :palettes (st/emit! (dcol/fetch-color-library library-id))))
                  :deps (mf/deps library-id)})
  (let [libraries (case section
                      :icons (mf/deref icon-libraries-ref)
                      :images (mf/deref image-libraries-ref)
                      :palettes (mf/deref color-libraries-ref))
        items (mf/deref selected-items-ref)
        selected-library (first (filter #(= (:id %) library-id) libraries))]
    [:div.library-page
     [:& library-header {:section section :team-id team-id}]
     [:& library-sidebar {:items libraries :team-id team-id :library-id library-id :section section}]

     (when library-id
       [:section.library-content
        [:& library-top-menu {:selected selected-library :section section :library-id library-id}]
        [:div.library-page-cards-container
         (for [item items]
           (let [item (assoc item :key (:id item))]
             (case section
               :icons [:& library-icon-card item]
               :images [:& library-image-card item]
               :palettes [:& library-color-card item ])))]])]))
