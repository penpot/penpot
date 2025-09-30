;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC
(ns app.main.ui.inspect.styles.rows.color-properties-row
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.types.color :as cc]
   [app.config :as cfg]
   [app.main.ui.ds.buttons.button :refer [button*]]
   [app.main.ui.ds.tooltip :refer [tooltip*]]
   [app.main.ui.formats :as fmt]
   [app.main.ui.inspect.styles.property-detail-copiable :refer [property-detail-copiable*]]
   [app.util.color :as uc]
   [app.util.i18n :refer [tr]]
   [app.util.timers :as tm]
   [app.util.webapi :as wapi]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(def ^:private schema:color-properties-row
  [:map
   [:term :string]
   [:color :any] ;; color object with :color, :gradient or :image
   [:format {:optional true} :string] ;; color format, e.g., "hex", "rgba", etc.
   [:token {:optional true} :any] ;; resolved token object
   [:copiable {:optional true} :boolean]])

(mf/defc color-properties-row*
  {::mf/schema schema:color-properties-row}
  [{:keys [class term color format token]}]
  (let [copied* (mf/use-state false)
        copied (deref copied*)

        color-value (:color color)
        color-gradient (:gradient color)
        color-image (:image color)
        color-image-name (:name color-image)
        color-image-url (when (some? color-image)
                          (cfg/resolve-file-media color-image))
        color-opacity (mf/use-memo
                       (mf/deps color)
                       #(dm/str (-> color
                                    (:opacity)
                                    (d/coalesce 1)
                                    (* 100)
                                    (fmt/format-number)) "%"))

        formatted-color-value (mf/use-memo
                               (mf/deps color)
                               #(cond
                                  (some? (:color color)) (case format
                                                           "hex" (dm/str color-value " " color-opacity)
                                                           "rgba" (let [[r g b a] (cc/hex->rgba color-value color-opacity)
                                                                        result (cc/format-rgba [r g b a])]
                                                                    result)
                                                           "hsla" (let [[h s l a] (cc/hex->hsla color-value color-opacity)
                                                                        result (cc/format-hsla [h s l a])]
                                                                    result)
                                                           color-value)
                                  (some? (:gradient color)) (uc/gradient-type->string (:type color-gradient))
                                  (some? (:image color)) (tr "media.image")
                                  :else "none"))

        css-term (-> term
                     (str/replace #" " "-")
                     (str/replace #"([A-Z])" "-$1")
                     (str/lower)
                     (str/replace #"^-" ""))

        copiable-value (mf/use-memo
                        (mf/deps color formatted-color-value color-opacity color-image-url token)
                        #(if (some? token)
                           (:name token)
                           (cond
                             (:color color) (if (= format "hex")
                                              (dm/str css-term ": " color-value "; opacity: " color-opacity ";")
                                              (dm/str css-term ": " formatted-color-value ";"))
                             (:gradient color) (dm/str css-term ": " (uc/color->background color) ";")
                             (:image color) (dm/str css-term ": url(" color-image-url ") no-repeat center center / cover;")
                             :else "none")))
        copy-attr
        (mf/use-fn
         (mf/deps copied formatted-color-value)
         (fn []
           (reset! copied* true)
           (wapi/write-to-clipboard copiable-value)
           (tm/schedule 1000 #(reset! copied* false))))]
    [:*
     [:dl {:class [(stl/css :property-row) class]}
      [:dt {:class (stl/css :property-term)} term]
      [:dd {:class (stl/css :property-detail)}
       (if token
         [:> tooltip* {:id (:name token)
                       :class (stl/css :tooltip-token-wrapper)
                       :content #(mf/html
                                  [:div {:class (stl/css :tooltip-token)}
                                   [:div {:class (stl/css :tooltip-token-title)}
                                    (tr "inspect.tabs.styles.token.resolved-value")]
                                   [:div {:class (stl/css :tooltip-token-value)}
                                    (:value token)]])}
          [:> property-detail-copiable* {:detail formatted-color-value
                                         :color color
                                         :token token
                                         :copied copied
                                         :on-click copy-attr}]]


         [:> property-detail-copiable* {:detail formatted-color-value
                                        :color color
                                        :copied copied
                                        :on-click copy-attr}])]]
     (when (:image color)
       [:div {:class (stl/css :color-image-preview)}
        [:div {:class (stl/css :color-image-preview-wrapper)}
         [:img {:class (stl/css :color-image)
                :src color-image-url
                :title color-image-name
                :alt ""}]]
        [:> button* {:variant "secondary"
                     :to color-image-url
                     :target "_blank"
                     :download color-image-name}
         (tr "inspect.attributes.image.download")]])]))

