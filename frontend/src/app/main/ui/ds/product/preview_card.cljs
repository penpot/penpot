;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.ds.product.preview-card
  (:require-macros
   [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.main.ui.ds.foundations.typography :as t]
   [app.main.ui.ds.foundations.typography.text :refer [text*]]
   [rumext.v2 :as mf]))

(def ^:private schema:preview-card
  [:map
   [:class {:optional true} [:maybe :string]]
   [:label :string]
   [:side {:optional true} [:maybe [:enum "base" "main" "branch" "result"]]]
   [:detail {:optional true} [:maybe :string]]
   [:caption {:optional true} [:maybe :string]]
   [:is-warning {:optional true} [:maybe :boolean]]
   [:is-large {:optional true} [:maybe :boolean]]])

(mf/defc preview-card*
  "A framed preview of one side of a comparison. The side picks the
  colour of the frame and of the label: base is neutral,
  main purple, the branch the accent and the merged result green.
  `detail` follows the label (who or when); `caption` sits under the
  preview, in the warning colour when `is-warning` says so. `is-large`
  gives the preview a taller stage, for a view that has the room."
  {::mf/schema schema:preview-card}
  [{:keys [class label side detail caption is-warning is-large children] :rest props}]
  (let [side       (d/nilv side "base")
        is-warning (d/nilv is-warning false)
        is-large   (d/nilv is-large false)
        props      (mf/spread-props props
                                    {:class [class (stl/css-case :preview-card true
                                                                 :side-main (= side "main")
                                                                 :side-branch (= side "branch")
                                                                 :side-result (= side "result"))]})]
    [:> :div props
     [:div {:class (stl/css :head)}
      [:> text* {:as "span" :typography t/headline-small :class (stl/css :label)} label]
      (when detail
        [:> text* {:as "span" :typography t/body-small :class (stl/css :detail)} detail])]
     [:div {:class (stl/css-case :stage true :is-large is-large)} children]
     (when caption
       [:> text* {:as "span"
                  :typography t/body-small
                  :class (stl/css-case :caption true :is-warning is-warning)}
        caption])]))
