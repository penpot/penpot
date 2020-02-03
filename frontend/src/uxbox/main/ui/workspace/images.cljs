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
   [uxbox.common.data :as d]
   [uxbox.main.data.images :as udi]
   [uxbox.main.data.workspace :as dw]
   [uxbox.main.store :as st]
   [uxbox.main.refs :as refs]
   [uxbox.main.ui.modal :as modal]
   [uxbox.util.dom :as dom]
   [uxbox.util.i18n :as i18n :refer [tr t]]
   [uxbox.util.uuid :as uuid]))

;; --- Refs

(def ^:private collections-iref
  (-> (l/key :images-collections)
      (l/derive st/state)))

(def ^:private images-iref
  (-> (l/key :images)
      (l/derive st/state)))

(def ^:private workspace-images-iref
  (-> (comp (l/key :workspace-images)
            (l/lens vals))
      (l/derive st/state)))

(def ^:private uploading-iref
  (-> (l/in [:workspace-local :uploading])
      (l/derive st/state)))

;; --- Import Image Modal

(declare import-image-from-coll-modal)

(mf/defc import-image-modal
  [props]
  (let [input (mf/use-ref nil)
        uploading? (mf/deref uploading-iref)

        on-upload-click #(dom/click (mf/ref-node input))

        on-uploaded
        (fn [{:keys [id name] :as image}]
          (let [shape {:name name
                       :metadata {:width (:width image)
                                  :height (:height image)
                                  :uri (:uri image)
                                  :thumb-width (:thumb-width image)
                                  :thumb-height (:thumb-height image)
                                  :thumb-uri (:thumb-uri image)}}]
            (st/emit! (dw/select-for-drawing :image shape))
            (modal/hide!)))

        on-files-selected
        (fn [event]
          (st/emit! (-> (dom/get-target event)
                        (dom/get-files)
                        (array-seq)
                        (first)
                        (dw/upload-image on-uploaded))))

        on-select-from-library
        (fn [event]
          (dom/prevent-default event)
          (modal/show! import-image-from-coll-modal {}))

        on-close
        (fn [event]
          (dom/prevent-default event)
          (modal/hide!))]
    [:div.lightbox-body
     [:h3 (tr "image.new")]
     [:div.row-flex

      ;; Select from collections
      [:div.lightbox-big-btn {:on-click on-select-from-library}
       [:span.big-svg i/image]
       [:span.text (tr "image.select")]]

      ;; Select from workspace
      [:div.lightbox-big-btn {:on-click on-select-from-library}
       [:span.big-svg i/image]
       [:span.text (tr "image.select")]]

      ;; Direct image upload
      [:div.lightbox-big-btn {:on-click on-upload-click}
       (if uploading?
         [:span.big-svg.upload i/loader-pencil]
         [:span.big-svg.upload i/exit])
       [:span.text (tr "image.upload")]
       [:input.upload-image-input
        {:style {:display "none"}
         :multiple false
         :accept "image/jpeg,image/png,image/webp"
         :type "file"
         :ref input
         :on-change on-files-selected}]]]
     [:a.close {:on-click on-close} i/close]]))

;; --- Import Image from Collection Modal

(mf/defc image-item
  [{:keys [image] :as props}]
  (letfn [(on-click [event]
            ;; TODO: deduplicate this code...
            (let [shape {:name (:name image)
                         :metadata {:width (:width image)
                                    :height (:height image)
                                    :uri (:uri image)
                                    :thumb-width (:thumb-width image)
                                    :thumb-height (:thumb-height image)
                                    :thumb-uri (:thumb-uri image)}}]
              (st/emit! (dw/select-for-drawing :image shape))
              (modal/hide!)))]
    [:div.library-item {:on-click on-click}
     [:div.library-item-th
      {:style {:background-image (str "url('" (:thumb-uri image) "')")}}]
     [:span (:name image)]]))

(mf/defc import-image-from-coll-modal
  [props]
  (let [locale (i18n/use-locale)
        local (mf/use-state {:collection-id nil :tab :file})

        collections (mf/deref collections-iref)
        collections (->> (vals collections)
                         (sort-by :name))

        select-tab #(swap! local assoc :tab %)

        collection-id (or (:collection-id @local)
                          (:id (first collections)))

        tab (:tab @local)


        images (mf/deref images-iref)
        images (->> (vals images)
                    (filter #(= collection-id (:collection-id %))))

        workspace-images (mf/deref workspace-images-iref)

        on-close #(do (dom/prevent-default %)
                      (modal/hide!))

        on-change #(->> (dom/get-target %)
                        (dom/get-value)
                        (d/read-string)
                        (swap! local assoc :collection-id))]

    (mf/use-effect #(st/emit! udi/fetch-collections))
    (mf/use-effect
     {:deps (mf/deps collection-id)
      :fn #(when collection-id
             (st/emit! (udi/fetch-images collection-id)))})

    [:div.lightbox-body.big-lightbox
     [:h3 (tr "image.import-library")]
     [:div.import-img-library
      [:div.library-actions

       ;; Tabs
       [:ul.toggle-library
        [:li.your-images {:class (when (= tab :file) "current")
                          :on-click #(select-tab :file)}
         (t locale "ds.your-images-title")]
        [:li.standard {:class (when (not= tab :file) "current")
                       :on-click #(select-tab :collection)}
         (t locale "ds.store-images-title")]]

       ;; Collections dropdown
       (when (= tab :collection)
         [:select.input-select {:on-change on-change}
          (for [coll collections]
            (let [id (:id coll)
                  name (:name coll)]
              [:option {:key (str id) :value (pr-str id)} name]))])]

      (if (= tab :collection)
        [:div.library-content
         (for [image images]
           [:& image-item {:image image :key (:id image)}])]
        [:div.library-content
         (for [image workspace-images]
           [:& image-item {:image image :key (:id image)}])])]
     [:a.close {:href "#" :on-click on-close} i/close]]))
