;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2017 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2017 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.workspace.images
  (:require [lentes.core :as l]
            [rumext.core :as mx :include-macros true]
            [potok.core :as ptk]
            [uxbox.builtins.icons :as i]
            [uxbox.main.store :as st]
            [uxbox.main.data.lightbox :as udl]
            [uxbox.main.data.images :as udi]
            [uxbox.main.data.workspace :as udw]
            [uxbox.main.data.shapes :as uds]
            [uxbox.main.ui.lightbox :as lbx]
            [uxbox.util.i18n :as t :refer [tr]]
            [uxbox.util.data :refer [read-string jscoll->vec]]
            [uxbox.util.dom :as dom]
            [uxbox.util.uuid :as uuid]))

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

;; --- Components

(mx/defcs import-image-lightbox
  {:mixins [mx/static mx/reactive]}
  [own]
  (letfn [(on-upload-click [event]
            (let [input (mx/ref-node own "input")]
              (dom/click input)))
          (on-uploaded [[image]]
            (let [{:keys [id name width height]} image
                  shape {:type :image
                         :name name
                         :id (uuid/random)
                         :metadata {:width width
                                    :height height}
                         :image id}]
              (st/emit! (udw/select-for-drawing shape))
              (udl/close!)))
          (on-files-selected [event]
            (let [files (dom/get-event-files event)
                  files (jscoll->vec files)]
              (st/emit! (udi/create-images nil files on-uploaded))))
          (on-select-from-library [event]
            (dom/prevent-default event)
            (udl/open! :import-image-from-collections))
          (on-close [event]
            (dom/prevent-default event)
            (udl/close!))]
    (let [uploading? (mx/react uploading?-ref)]
      [:div.lightbox-body {}
       [:h3 {} "New image"]
       [:div.row-flex {}
        [:div.lightbox-big-btn {:on-click on-select-from-library}
         [:span.big-svg {} i/image]
         [:span.text {} "Select from library"]]
        [:div.lightbox-big-btn {:on-click on-upload-click}
         (if uploading?
           [:span.big-svg.upload {} i/loader-pencil]
           [:span.big-svg.upload {} i/exit])
         [:span.text {} "Upload file"]
         [:input.upload-image-input
          {:style {:display "none"}
           :accept "image/jpeg,image/png"
           :type "file"
           :ref "input"
           :on-change on-files-selected}]]]
       [:a.close {:on-click on-close} i/close]])))

(mx/defc image-item
  {:mixins [mx/static]}
  [{:keys [thumbnail name id width height] :as image}]
  (letfn [(on-click [event]
            (let [shape {:type :image
                         :name name
                         :id (uuid/random)
                         :metadata {:width width
                                    :height height}
                         :image id}]
              (st/emit! (udw/select-for-drawing shape))
              (udl/close!)))]
    [:div.library-item {:key (str id)
                        :on-click on-click}
     [:div.library-item-th
      {:style {:background-image (str "url('" thumbnail "')")}}]
     [:span {} name]]))

(mx/defc image-collection
  {:mixins [mx/static]}
  [images]
  [:div.library-content {}
   (for [image images]
     (-> (image-item image)
         (mx/with-key (str (:id image)))))])

(defn will-mount
  [own]
  (let [local (:rum/local own)]
    (st/emit! (udi/fetch-collections))
    (st/emit! (udi/fetch-images nil))
    (add-watch local ::key (fn [_ _ _ v]
                             (st/emit! (udi/fetch-images (:id v)))))
    own))

(defn will-unmount
  [own]
  (let [local (:rum/local own)]
    (remove-watch local ::key)
    own))

(mx/defcs image-collections-lightbox
  {:mixins [mx/reactive (mx/local)]
   :will-mount will-mount
   :will-unmount will-unmount}
  [own]
  (let [local (:rum/local own)
        id (:id @local)
        type (:type @local :own)
        own? (= type :own)
        builtin? (= type :builtin)
        colls (mx/react collections-ref)
        colls (->> (vals colls)
                   (filter #(= type (:type %)))
                   (sort-by :name))
        id (if (and (nil? id) builtin?)
             (:id (first colls) ::no-value)
             id)
        images (mx/react images-ref)
        images (->> (vals images)
                    (filter #(= id (:collection %))))]
    (letfn [(on-close [event]
              (dom/prevent-default event)
              (udl/close!))
            (select-type [event type]
              (swap! local assoc :type type))
            (on-coll-change [event]
              (let [value (dom/event->value event)
                    value (read-string value)]
                (swap! local assoc :id value)))]
      [:div.lightbox-body.big-lightbox {}
       [:h3 {} "Import image from library"]
       [:div.import-img-library {}
        [:div.library-actions {}
         [:ul.toggle-library {}
          [:li.your-images {:class (when own? "current")
                            :on-click #(select-type % :own)}
           "YOUR IMAGES"]
          [:li.standard {:class (when builtin? "current")
                         :on-click #(select-type % :builtin)}
           "IMAGES STORE"]]
         [:select.input-select {:on-change on-coll-change}
          (when own?
            [:option {:value (pr-str nil)} "Storage"])
          (for [coll colls]
            (let [id (:id coll)
                  name (:name coll)]
              [:option {:key (str id) :value (pr-str id)} name]))]]
        (image-collection images)]
       [:a.close {:href "#" :on-click on-close} i/close]])))

(defmethod lbx/render-lightbox :import-image
  [_]
  (import-image-lightbox))


(defmethod lbx/render-lightbox :import-image-from-collections
  [_]
  (image-collections-lightbox))
