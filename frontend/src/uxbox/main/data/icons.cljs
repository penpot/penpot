;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.data.icons
  (:require
   [cljs.spec.alpha :as s]
   [beicon.core :as rx]
   [cuerdas.core :as str]
   [potok.core :as ptk]
   [uxbox.common.spec :as us]
   [uxbox.common.data :as d]
   [uxbox.main.repo :as rp]
   [uxbox.main.store :as st]
   [uxbox.util.dom :as dom]
   [uxbox.util.webapi :as wapi]
   [uxbox.util.i18n :as i18n :refer [t tr]]
   [uxbox.util.router :as r]
   [uxbox.common.uuid :as uuid]))

(s/def ::id uuid?)
(s/def ::name string?)
(s/def ::created-at inst?)
(s/def ::modified-at inst?)
(s/def ::user-id uuid?)
(s/def ::collection-id ::us/uuid)

(s/def ::collection
  (s/keys :req-un [::id
                   ::name
                   ::created-at
                   ::modified-at
                   ::user-id]))

;; --- Create Icon
(defn- parse-svg
  [data]
  (s/assert ::us/string data)
  (let [valid-tags #{"defs" "path" "circle" "rect" "metadata" "g"
                     "radialGradient" "stop"}
        div (dom/create-element "div")
        gc (dom/create-element "div")
        g (dom/create-element "http://www.w3.org/2000/svg" "g")
        _ (dom/set-html! div data)
        svg (dom/query div "svg")]
    (loop [child (dom/get-first-child svg)]
      (if child
        (let [tagname (dom/get-tag-name child)]
          (if  (contains? valid-tags tagname)
            (dom/append-child! g child)
            (dom/append-child! gc child))
          (recur (dom/get-first-child svg)))
        (let [width (.. ^js svg -width -baseVal -value)
              height (.. ^js svg -height -baseVal -value)
              view-box [(.. ^js svg -viewBox -baseVal -x)
                        (.. ^js svg -viewBox -baseVal -y)
                        (.. ^js svg -viewBox -baseVal -width)
                        (.. ^js svg -viewBox -baseVal -height)]
              props {:width width
                     :mimetype "image/svg+xml"
                     :height height
                     :view-box view-box}]
          [(dom/get-outer-html g) props])))))


(declare create-icon-result)

(defn create-icons
  [library-id files]
  (s/assert (s/nilable uuid?) library-id)
  (ptk/reify ::create-icons
    ptk/WatchEvent
    (watch [_ state s]
      (letfn [(parse [file]
                (->> (wapi/read-file-as-text file)
                     (rx/map parse-svg)))
              (allowed? [file]
                (= (.-type file) "image/svg+xml"))
              (prepare [[content metadata]]
                {:library-id library-id
                 :content content
                 :id (uuid/next)
                 ;; TODO Keep the name of the original icon
                 :name (str "Icon " (gensym "i"))
                 :metadata metadata})]
        (->> (rx/from files)
             (rx/filter allowed?)
             (rx/merge-map parse)
             (rx/map prepare)
             (rx/flat-map #(rp/mutation! :create-icon %))
             (rx/map (partial create-icon-result library-id)))))))

(defn create-icon-result
  [library-id item]
  (ptk/reify ::create-icon-result
    ptk/UpdateEvent
    (update [_ state]
      (let [{:keys [id] :as item} (assoc item :type :icon)]
        (-> state
            (update-in [:library-items :icons library-id] #(into [item] %)))))))
