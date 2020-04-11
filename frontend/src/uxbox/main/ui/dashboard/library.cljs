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
   [okulary.core :as l]
   [rumext.alpha :as mf]
   [cuerdas.core :as str]
   [uxbox.util.router :as rt]
   [uxbox.util.i18n :as i18n :refer [t tr]]
   [uxbox.util.color :as uc]
   [uxbox.util.dom :as dom]
   [uxbox.util.time :as dt]
   [uxbox.main.data.library :as dlib]
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
   [uxbox.main.ui.components.editable-label :refer [editable-label]]
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

(defn create-library [section team-id]
  (let [name (str (str (str/title (name section)) " " (gensym "Library ")))]
    (st/emit! (dlib/create-library section team-id name))))

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
              (dlib/retrieve-libraries :icons (:id item))
              (st/emit! (rt/nav path {:team-id team-id :library-id (:id item)}))))}
         [:& editable-label {:value (:name item)
                             :on-change #(st/emit! (dlib/rename-library section team-id library-id %))}]
         ])]]))

(mf/defc library-top-menu
  [{:keys [selected section library-id team-id on-delete-selected]}]
  (let [state (mf/use-state {:is-open false
                             :editing-name false})
        locale (i18n/use-locale)
        stop-editing #(swap! state assoc :editing-name false)]
    [:header.library-top-menu
     [:div.library-top-menu-current-element
      [:& editable-label {:edit (:editing-name @state)
                          :on-change #(do
                                        (stop-editing)
                                        (st/emit! (dlib/rename-library section team-id library-id %)))
                          :on-cancel #(swap! state assoc :editing-name false)
                          :class-name "library-top-menu-current-element-name"
                          :value (:name selected)}]
      [:a.library-top-menu-current-action
       { :on-click #(swap! state update :is-open not)}
       [:span i/arrow-down]]
      [:& context-menu
       {:show (:is-open @state)
        :on-close #(swap! state update :is-open not)
        :options [[(t locale "ds.button.rename")
                   #(swap! state assoc :editing-name true)]

                  [(t locale "ds.button.delete")
                   (fn []
                     (let [path (keyword (str "dashboard-library-" (name section) "-index"))]
                       (modal/show!
                        confirm-dialog
                        {:on-accept #(do
                                       (st/emit! (dlib/delete-library section team-id library-id))
                                       (st/emit! (rt/nav path {:team-id team-id})))
                         :message "Are you sure you want to delete this library?"
                         :accept-text "Delete"})))]]}]]

     [:div.library-top-menu-actions
      [:a.library-top-menu-actions-delete
       {:on-click #(when on-delete-selected (on-delete-selected))}
       i/trash]

      (if (= section :palettes)
        [:button.btn-dashboard
         {:on-click #(create-item section library-id)}
         (t locale (str "dashboard.library.add-item." (name section)))]

        [:*
         [:label {:for "file-upload" :class-name "btn-dashboard"}
          (t locale (str "dashboard.library.add-item." (name section)))]
         [:input {:on-change #(create-item section library-id %)
                  :id "file-upload"
                  :type "file"
                  :multiple true
                  :accept (case section
                            :images "image"
                            :icons "image/svg+xml"
                            "")
                  :style {:display "none"}}]])]]))

(mf/defc library-icon-card
  [{:keys [item on-select on-unselect]}]
  (let [{:keys [id name url content metadata library-id modified-at]} item
        locale (i18n/use-locale)
        state (mf/use-state {:is-open false
                             :selected false})
        time (dt/timeago modified-at {:locale locale})
        handle-change (fn []
                        (swap! state update :selected not)
                        (if (:selected @state)
                          (when on-unselect (on-unselect id))
                          (when on-select (on-select id))))]
    [:div.library-card.library-icon
     [:div.input-checkbox.check-primary
      [:input {:type "checkbox"
               :id (str "icon-" id)
               :on-change handle-change
               :checked (:selected @state)}]
      [:label {:for (str "icon-" id)}]]
     [:div.library-card-image
      [:svg {:view-box (->> metadata :view-box (str/join " "))
             :width (:width metadata)
             :height (:height metadata)
             :dangerouslySetInnerHTML {:__html content}}]]

     [:div.library-card-footer
      [:div.library-card-footer-name name]
      [:div.library-card-footer-timestamp time]
      [:div.library-card-footer-menu
       { :on-click #(swap! state update :is-open not) }
       i/actions]
      [:& context-menu
       {:show (:is-open @state)
        :on-close #(swap! state update :is-open not)
        :options [[(t locale "ds.button.delete")
                   (fn []
                     (modal/show!
                      confirm-dialog
                      {:on-accept #(st/emit! (dlib/delete-item :icons library-id id))
                       :message "Are you sure you want to delete this icon?"
                       :accept-text "Delete"}))]]}]]]))

(mf/defc library-image-card
  [{:keys [item on-select on-unselect]}]
  (let [{:keys [id name thumb-uri library-id modified-at]} item
        locale (i18n/use-locale)
        state (mf/use-state {:is-open false})
        time (dt/timeago modified-at {:locale locale})
        handle-change (fn []
                        (swap! state update :selected not)
                        (if (:selected @state)
                          (when on-unselect (on-unselect id))
                          (when on-select (on-select id))))]
    [:div.library-card.library-image
     [:div.input-checkbox.check-primary
      [:input {:type "checkbox"
               :id (str "image-" id)
               :on-change handle-change
               :checked (:selected @state)}]
      [:label {:for (str "image-" id)}]]
     [:div.library-card-image
      [:img {:src thumb-uri}]]
     [:div.library-card-footer
      [:div.library-card-footer-name name]
      [:div.library-card-footer-timestamp time]
      [:div.library-card-footer-menu
       { :on-click #(swap! state update :is-open not) }
       i/actions]
      [:& context-menu
       {:show (:is-open @state)
        :on-close #(swap! state update :is-open not)
        :options [[(t locale "ds.button.delete")
                   (fn []
                     (modal/show!
                      confirm-dialog
                      {:on-accept #(st/emit! (dlib/delete-item :images library-id id))
                       :message "Are you sure you want to delete this image?"
                       :accept-text "Delete"}))]]}]]]))

(mf/defc library-color-card
  [{:keys [item on-select on-unselect]}]
  (let [{:keys [ id content library-id modified-at]} item
        locale (i18n/use-locale)
        state (mf/use-state {:is-open false})
        handle-change (fn []
                        (swap! state update :selected not)
                        (if (:selected @state)
                          (when on-unselect (on-unselect id))
                          (when on-select (on-select id))))]
    (when content
      [:div.library-card.library-color
       [:div.input-checkbox.check-primary
        [:input {:type "checkbox"
                 :id (str "color-" id)
                 :on-change handle-change
                 :checked (:selected @state)}]
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
          :options [[(t locale "ds.button.delete")
                     (fn []
                       (modal/show!
                        confirm-dialog
                        {:on-accept #(st/emit! (dlib/delete-item :palettes library-id id))
                         :message "Are you sure you want to delete this color?"
                         :accept-text "Delete"}))]]}]]])))

(defn libraries-ref [section team-id]
  (-> (l/in [:library section team-id])
      (l/derived st/state)))

(defn selected-items-ref [section library-id]
  (-> (l/in [:library-items section library-id])
      (l/derived st/state)))

(def last-deleted-library-ref
  (-> (l/in [:library :last-deleted-library])
      (l/derived st/state)))

(mf/defc library-page
  [{:keys [team-id library-id section]}]
  (let [state (mf/use-state {:selected #{}})
        libraries (mf/deref (libraries-ref section team-id))
        items (mf/deref (selected-items-ref section library-id))
        last-deleted-library (mf/deref last-deleted-library-ref)
        selected-library (first (filter #(= (:id %) library-id) libraries))]

    (mf/use-effect
     (mf/deps libraries)
     #(if (and (nil? library-id) (> (count libraries) 0))
        (let [path (keyword (str "dashboard-library-" (name section)))]
          (st/emit! (rt/nav path {:team-id team-id :library-id (:id (first libraries))})))))

    (mf/use-effect
     (mf/deps libraries)
     #(if (and library-id (not (some (fn [{id :id}] (= library-id id)) libraries)))
        (let [path (keyword (str "dashboard-library-" (name section) "-index"))]
          (st/emit! (rt/nav path {:team-id team-id})))))

    (mf/use-effect
     (mf/deps section team-id)
     #(st/emit! (dlib/retrieve-libraries section team-id)))

    (mf/use-effect
     (mf/deps library-id last-deleted-library)
     #(when (and library-id (not= last-deleted-library library-id))
        (st/emit! (dlib/retrieve-library-data section library-id))))

    [:div.library-page
     [:& library-header {:section section :team-id team-id}]
     [:& library-sidebar {:items libraries :team-id team-id :library-id library-id :section section}]

     (if library-id
       [:section.library-content
        [:& library-top-menu
         {:selected selected-library
          :section section
          :library-id library-id
          :team-id team-id
          :on-delete-selected
          (fn []
            (when (-> @state :selected count (> 0))
              (modal/show!
               confirm-dialog
               {:on-accept #(st/emit! (dlib/batch-delete-item section library-id (:selected @state)))
                :message (str "Are you sure you want to delete " (-> @state :selected count) " items?")
                :accept-text "Delete"})
              )
            )
          }]
        [:*
         ;; TODO: Fix the chunked list
         #_[:& chunked-list {:items items
                             :initial-size 30
                             :chunk-size 30}
            (fn [item]
              (let [item (assoc item :key (:id item))]
                (case section
                  :icons [:& library-icon-card item]
                  :images [:& library-image-card item]
                  :palettes [:& library-color-card item ])))]
         (if (> (count items) 0)
           [:div.library-page-cards-container
            (for [item items]
              (let [item (assoc item :key (:id item))
                    props {:item item
                           :key (:id item)
                           :on-select #(swap! state update :selected conj %)
                           :on-unselect #(swap! state update :selected disj %)}]
                (case section
                  :icons [:& library-icon-card props]
                  :images [:& library-image-card props]
                  :palettes [:& library-color-card props])))]
           [:div.library-content-empty
            [:p.library-content-empty-text "You still have no elements in this library"]])]]

       [:div.library-content-empty
        [:p.library-content-empty-text "You still have no image libraries."]])]))
