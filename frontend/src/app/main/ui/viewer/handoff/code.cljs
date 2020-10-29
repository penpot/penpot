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
   [rumext.alpha :as mf]
   [app.util.i18n :as i18n]
   [app.main.ui.icons :as i]
   [app.common.geom.shapes :as gsh]))

(def css-example
  "/* text layer name */
.shape {
    width: 142px;
    height: 40px;
    border-radius: 20px;
    background-color: var(--tiffany-blue);
}")

(def svg-example
  "<g class=\"shape\">
  <rect fill=\"#ffffff\" fill-opacity=\"1\" x=\"629\" y=\"169\" id=\"shape-eee5fa10-5336-11ea-
8394-2dd26e322db3\" width=\"176\" height=\"211\">
  </rect>
</g>")

(mf/defc code-block [{:keys [code type]}]
  (let [block-ref (mf/use-ref)]
    (mf/use-effect
     (mf/deps block-ref)
     (fn []
       (hljs/highlightBlock (mf/ref-val block-ref))))
    [:pre.code-display {:class type
                        :ref block-ref} code]))

(mf/defc code
  [{:keys [shapes frame]}]
  (let [locale (mf/deref i18n/locale)
        shapes (->> shapes
                    (map #(gsh/translate-to-frame % frame)))]
    [:div.element-options
     [:div.code-block
      [:div.code-row-lang
       [:select.code-selection
        [:option "CSS"]
        [:option "SASS"]
        [:option "Less"]
        [:option "Stylus"]]

       [:button.attributes-copy-button
        {:on-click #(prn "??")}
        i/copy]]

      [:div.code-row-display
       [:& code-block {:type "css"
                       :code css-example}]]]

     [:div.code-block
      [:div.code-row-lang
       [:select.code-selection
        [:option "SVG"]
        [:option "HTML"]]

       [:button.attributes-copy-button
        {:on-click #(prn "??")}
        i/copy]]

      [:div.code-row-display
       [:& code-block {:type "svg"
                       :code svg-example}]]]

     ]))

