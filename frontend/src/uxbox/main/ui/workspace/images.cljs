;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2017 Juan de la Cruz <delacruzgarciajuan@gmail.com>
;; Copyright (c) 2015-2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.ui.workspace.images
  (:require
   [lentes.core :as l]
   [rumext.alpha :as mf]
   [rumext.core :as mx]
   [uxbox.builtins.icons :as i]
   [uxbox.main.data.images :as udi]
   [uxbox.main.data.workspace :as dw]
   [uxbox.main.store :as st]
   [uxbox.main.ui.modal :as modal]
   [uxbox.util.data :refer [read-string jscoll->vec]]
   [uxbox.util.dom :as dom]
   [uxbox.util.i18n :as t :refer [tr]]
   [uxbox.util.uuid :as uuid]))

;; --- Refs

(def ^:private collections-iref
  (-> (l/key :images-collections)
      (l/derive st/state)))

(def ^:private images-iref
  (-> (l/key :images)
      (l/derive st/state)))

(def ^:private uploading-iref
  (-> (l/in [:dashboard :images :uploading])
      (l/derive st/state)))

;; --- Import Image Modal

(declare import-image-from-coll-modal)

(mf/defc import-image-modal
  [props]
  (let [input (mf/use-ref nil)
        uploading? (mf/deref uploading-iref)]
    (letfn [(on-upload-click [event]
              (let [input-el (mf/ref-node input)]
                (dom/click input-el)))

            (on-uploaded [[image]]
              (let [{:keys [id name width height]} image
                    shape {:name name
                           :metadata {:width width
                                      :height height}
                           :image id}]
                (st/emit! (dw/select-for-drawing :image shape))
                (modal/hide!)))

            (on-files-selected [event]
              (let [files (dom/get-event-files event)
                    files (jscoll->vec files)]
                (st/emit! (udi/create-images nil files on-uploaded))))

            (on-select-from-library [event]
              (dom/prevent-default event)
              (modal/show! import-image-from-coll-modal {}))

            (on-close [event]
              (dom/prevent-default event)
              (modal/hide!))]
      [:div.lightbox-body
       [:h3 (tr "image.new")]
       [:div.row-flex
        [:div.lightbox-big-btn {:on-click on-select-from-library}
         [:span.big-svg i/image]
         [:span.text (tr "image.select")]]
        [:div.lightbox-big-btn {:on-click on-upload-click}
         (if uploading?
           [:span.big-svg.upload i/loader-pencil]
           [:span.big-svg.upload i/exit])
         [:span.text (tr "image.upload")]
         [:input.upload-image-input
          {:style {:display "none"}
           :accept "image/jpeg,image/png"
           :type "file"
           :ref input
           :on-change on-files-selected}]]]
       [:a.close {:on-click on-close} i/close]])))

;; --- Import Image from Collection Modal

(mf/defc image-item
  [{:keys [image] :as props}]
  (letfn [(on-click [event]
            (let [shape {:name (:name image)
                         :metadata {:width (:width image)
                                    :height (:height image)}
                         :image (:id image)}]
              (st/emit! (dw/select-for-drawing :image shape))
              (modal/hide!)))]
    [:div.library-item {:on-click on-click}
     [:div.library-item-th
      {:style {:background-image (str "url('" (:thumbnail image) "')")}}]
     [:span (:name image)]]))

(mf/defc image-collection
  [{:keys [images] :as props}]
  [:div.library-content
   (for [image images]
     [:& image-item {:image image :key (:id image)}])])

(mf/defc import-image-from-coll-modal
  [props]
  (let [local (mf/use-state {:id nil :type :own})
        id (:id @local)
        type (:type @local)
        own? (= type :own)
        builtin? (= type :builtin)
        colls (mf/deref collections-iref)
        colls (->> (vals colls)
                   (filter #(= type (:type %)))
                   (sort-by :name))
        images (mf/deref images-iref)
        images (->> (vals images)
                    (filter #(= id (:collection %))))
        on-close #(do (dom/prevent-default %)
                      (modal/hide!))
        select-type #(swap! local assoc :type %)
        on-change #(-> (dom/event->value %)
                       (read-string)
                       (swap! local assoc :id))]

    (mf/use-effect
     {:fn #(do (st/emit! (udi/fetch-collections))
               (st/emit! (udi/fetch-images nil)))})

    (mf/use-effect
     {:deps #js [type id]
      :fn #(st/emit! (udi/fetch-images id))})

    [:div.lightbox-body.big-lightbox
     [:h3 (tr "image.import-library")]
     [:div.import-img-library
      [:div.library-actions
       [:ul.toggle-library
        [:li.your-images {:class (when own? "current")
                          :on-click #(select-type :own)}
         (tr "ds.your-images-title")]
        [:li.standard {:class (when builtin? "current")
                       :on-click #(select-type :builtin)}
         (tr "ds.store-images-title")]]
       [:select.input-select {:on-change on-change}
        (when own?
          [:option {:value (pr-str nil)} "Storage"])
        (for [coll colls]
          (let [id (:id coll)
                name (:name coll)]
            [:option {:key (str id) :value (pr-str id)} name]))]]

      [:& image-collection {:images images}]]
     [:a.close {:href "#" :on-click on-close} i/close]]))
