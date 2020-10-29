;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.viewer.handoff.code
  (:require
   ["highlight.js" :as hljs]
   [cuerdas.core :as str]
   [rumext.alpha :as mf]
   [app.util.i18n :as i18n]
   [app.util.color :as uc]
   [app.util.webapi :as wapi]
   [app.util.code-gen :as cg]
   [app.main.ui.icons :as i]
   [app.common.geom.shapes :as gsh]))

(def svg-example
  "<rect
  x=\"629\"
  y=\"169\"
  width=\"176\"
  height=\"211\"
  fill=\"#ffffff\"
  fill-opacity=\"1\">
</rect>")


(defn generate-markup-code [type shapes]
  svg-example)

(mf/defc code-block [{:keys [code type]}]
  (let [block-ref (mf/use-ref)]
    (mf/use-effect
     (mf/deps code type block-ref)
     (fn []
       (hljs/highlightBlock (mf/ref-val block-ref))))
    [:pre.code-display {:class type
                        :ref block-ref} code]))

(mf/defc code
  [{:keys [shapes frame]}]
  (let [style-type (mf/use-state "css")
        markup-type (mf/use-state "svg")

        locale (mf/deref i18n/locale)
        shapes (->> shapes
                    (map #(gsh/translate-to-frame % frame)))

        style-code (cg/generate-style-code @style-type shapes)
        markup-code (generate-markup-code @markup-type shapes)]
    [:div.element-options
     [:div.code-block
      [:div.code-row-lang
       [:select.code-selection
        [:option {:value "css"} "CSS"]
        #_[:option {:value "sass"} "SASS"]
        #_[:option {:value "less"} "Less"]
        #_[:option {:value "stylus"} "Stylus"]]

       [:button.attributes-copy-button
        {:on-click #(wapi/write-to-clipboard style-code)}
        i/copy]]

      [:div.code-row-display
       [:& code-block {:type @style-type
                       :code style-code}]]]

     [:div.code-block
      [:div.code-row-lang
       [:select.code-selection
        [:option "SVG"]
        #_[:option "HTML"]]

       [:button.attributes-copy-button
        {:on-click #(wapi/write-to-clipboard markup-code)}
        i/copy]]

      [:div.code-row-display
       [:& code-block {:type @markup-type
                       :code markup-code}]]]

     ]))


