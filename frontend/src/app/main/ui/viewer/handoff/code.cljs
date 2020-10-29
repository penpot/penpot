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
   ["js-beautify" :as beautify]
   [cuerdas.core :as str]
   [rumext.alpha :as mf]
   [app.util.i18n :as i18n]
   [app.util.color :as uc]
   [app.util.dom :as dom]
   [app.util.webapi :as wapi]
   [app.util.code-gen :as cg]
   [app.main.ui.icons :as i]
   [app.common.geom.shapes :as gsh]))

(defn generate-markup-code [type shapes]
  (let [frame (dom/query js/document "#svg-frame")
        markup-shape
        (fn [shape]
          (let [selector (str "#shape-" (:id shape) (when (= :text (:type shape)) " .root"))]
            (when-let [el (and frame (dom/query frame selector))]
              (str
               (str/fmt "<!-- %s -->" (:name shape))
               (.-outerHTML el)))))]
    (->> shapes
         (map markup-shape )
         (remove nil?)
         (str/join "\n\n"))))

(mf/defc code-block [{:keys [code type]}]
  (let [code (-> code
                 (str/replace "<defs></defs>" "")
                 (str/replace "><" ">\n<"))
        code (cond-> code
               (= type "svg") (beautify/html #js {"indent_size" 2}))
        block-ref (mf/use-ref)]
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
        markup-code (mf/use-memo (mf/deps shapes) #(generate-markup-code @markup-type shapes))]
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
        [:option "HTML"]]

       [:button.attributes-copy-button
        {:on-click #(wapi/write-to-clipboard markup-code)}
        i/copy]]

      [:div.code-row-display
       [:& code-block {:type @markup-type
                       :code markup-code}]]]

     ]))
