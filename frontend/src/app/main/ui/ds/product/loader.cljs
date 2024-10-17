;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.ds.product.loader
  (:require-macros
   [app.common.data.macros :as dm]
   [app.main.style :as stl])
  (:require
   [app.common.math :as mth]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.v2 :as mf]))

(mf/defc loader-icon*
  {::mf/props :obj
   ::mf/private true}
  [{:keys [width height title] :rest props}]
  (let [class (stl/css :loader)
        props (mf/spread-props props {:viewBox "0 0 677.34762 182.15429"
                                      :role "status"
                                      :width width
                                      :height height
                                      :class class})]
    [:> "svg" props
     [:title title]
     [:g
      [:path {:d
              "M128.273 0l-3.9 2.77L0 91.078l128.273 91.076 549.075-.006V.008L128.273 0zm20.852 30l498.223.006V152.15l-498.223.007V30zm-25 9.74v102.678l-49.033-34.813-.578-32.64 49.61-35.225z"}]
      [:path {:class (stl/css :loader-line)
              :d
              "M134.482 157.147v25l518.57.008.002-25-518.572-.008z"}]]]))

(def ^:private schema:loader
  [:map
   [:class {:optional true} :string]
   [:width {:optional true} :int]
   [:height {:optional true} :int]
   [:title {:optional true} :string]
   [:overlay {:optional true} :boolean]])

(mf/defc loader*
  {::mf/props :obj
   ::mf/schema schema:loader}
  [{:keys [class width height title overlay children] :rest props}]

  (let [w (or width (when (some? height) (mth/ceil (* height (/ 100 27)))) 100)
        h (or height (when (some? width) (mth/ceil (* width (/ 27 100)))) 27)
        class (dm/str (or class "") " " (stl/css-case :wrapper true
                                                      :wrapper-overlay overlay))
        title (or title (tr "labels.loading"))
        props (mf/spread-props props {:class class})]

    [:> "div" props
     [:> loader-icon* {:title title
                       :width w
                       :height h}]
     children]))