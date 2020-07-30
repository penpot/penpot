;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.main.ui.workspace.sidebar.options.exports
  (:require
   [cuerdas.core :as str]
   [beicon.core :as rx]
   [rumext.alpha :as mf]
   [uxbox.common.data :as d]
   [uxbox.main.ui.icons :as i]
   [uxbox.main.data.messages :as dm]
   [uxbox.main.data.workspace :as udw]
   [uxbox.main.store :as st]
   [uxbox.util.object :as obj]
   [uxbox.util.dom :as dom]
   [uxbox.util.http-api :as http]
   [uxbox.util.i18n :as i18n :refer  [tr t]]))

(defn- request-export
  [shape exports]
  (http/send! {:method :post
               :uri "/export"
               :response-type :blob
               :auth true
               :body {:page-id (:page-id shape)
                      :object-id (:id shape)
                      :name (:name shape)
                      :exports exports}}))

(defn- trigger-download
  [name blob]
  (let [link (dom/create-element "a")
        uri  (dom/create-uri blob)]
    (obj/set! link "href" uri)
    (obj/set! link "download" (str/slug name))
    (obj/set! (.-style ^js link) "display" "none")
    (.appendChild (.-body ^js js/document) link)
    (.click link)
    (.remove link)))

(mf/defc exports-menu
  [{:keys [shape page] :as props}]
  (let [locale   (mf/deref i18n/locale)
        exports  (:exports shape [])
        loading? (mf/use-state false)

        on-download
        (mf/use-callback
         (mf/deps shape)
         (fn [event]
           (dom/prevent-default event)
           (swap! loading? not)
           (->> (request-export (assoc shape :page-id (:id page)) exports)
                (rx/subs
                 (fn [{:keys [status body] :as response}]
                   (js/console.log status body)
                   (if (= status 200)
                     (trigger-download (:name shape) body)
                     (st/emit! (dm/error (tr "errors.unexpected-error")))))
                 (constantly nil)
                 (fn []
                   (swap! loading? not))))))

        add-export
        (mf/use-callback
         (mf/deps shape)
         (fn []
           (let [xspec {:type :png
                        :suffix ""
                        :scale 1}]
             (st/emit! (udw/update-shape (:id shape)
                                         {:exports (conj exports xspec)})))))
        delete-export
        (mf/use-callback
         (mf/deps shape)
         (fn [index]
           (let [[before after] (split-at index exports)
                 exports (d/concat [] before (rest after))]
             (st/emit! (udw/update-shape (:id shape)
                                         {:exports exports})))))

        on-scale-change
        (mf/use-callback
         (mf/deps shape)
         (fn [index event]
           (let [target  (dom/get-target event)
                 value   (dom/get-value target)
                 value   (d/parse-double value)
                 exports (assoc-in exports [index :scale] value)]
             (st/emit! (udw/update-shape (:id shape)
                                         {:exports exports})))))

        on-suffix-change
        (mf/use-callback
         (mf/deps shape)
         (fn [index event]
           (let [target  (dom/get-target event)
                 value   (dom/get-value target)
                 exports (assoc-in exports [index :suffix] value)]
             (st/emit! (udw/update-shape (:id shape)
                                         {:exports exports})))))

        on-type-change
        (mf/use-callback
         (mf/deps shape)
         (fn [index event]
           (let [target  (dom/get-target event)
                 value   (dom/get-value target)
                 value   (keyword value)
                 exports (assoc-in exports [index :type] value)]
             (st/emit! (udw/update-shape (:id shape)
                                         {:exports exports})))))]

    [:div.element-set.exports-options
     [:div.element-set-title
      [:span (t locale "workspace.options.export")]
      [:div.add-page {:on-click add-export} i/close]]
     (when (seq exports)
       [:div.element-set-content
        (for [[index export] (d/enumerate exports)]
          [:div.element-set-options-group
           {:key index}
           [:select.input-select {:on-change (partial on-scale-change index)
                                  :value (:scale export)}
            [:option {:value "0.5"}  "0.5x"]
            [:option {:value "0.75"} "0.75x"]
            [:option {:value "1"} "1x"]
            [:option {:value "1.5"} "1.5x"]
            [:option {:value "2"} "2x"]
            [:option {:value "4"} "4x"]
            [:option {:value "6"} "6x"]]
           [:input.input-text {:value (:suffix export)
                               :on-change (partial on-suffix-change index)}]
           [:select.input-select {:value (name (:type export))
                                  :on-change (partial on-type-change index)}
            [:option {:value "png"} "PNG"]
            [:option {:value "jpeg"} "JPEG"]
            [:option {:value "svg"} "SVG"]]
           [:div.delete-icon {:on-click (partial delete-export index)}
            i/minus]])

        [:div.btn-icon-dark.download-button
         {:on-click (when-not @loading? on-download)
          :class (dom/classnames
                  :btn-disabled @loading?)
          :disabled @loading?}
         (if @loading?
           (t locale "workspace.options.exporting-object")
           (t locale "workspace.options.export-object"))]])]))

