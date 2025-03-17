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
   [app.common.data :as d]
   [app.common.math :as mth]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.timers :as tm]
   [beicon.v2.core :as rx]
   [rumext.v2 :as mf]))

(def tips
  [["tips.tip-01" "title" "message"]
   ["tips.tip-02" "title" "message"]
   ["tips.tip-03" "title" "message"]
   ["tips.tip-04" "title" "message"]
   ["tips.tip-05" "title" "message"]
   ["tips.tip-06" "title" "message"]
   ["tips.tip-07" "title" "message"]
   ["tips.tip-08" "title" "message"]
   ["tips.tip-09" "title" "message"]
   ["tips.tip-10" "title" "message"]])

(def ^:private
  svg:loader-path-1
  "M128.273 0l-3.9 2.77L0 91.078l128.273 91.076 549.075-.006V.008L128.273 0zm20.852 30l498.223.006V152.15l-498.223.007V30zm-25 9.74v102.678l-49.033-34.813-.578-32.64 49.61-35.225z")

(def ^:private
  svg:loader-path-2
  "M134.482 157.147v25l518.57.008.002-25-518.572-.008z")

(mf/defc loader-icon*
  {::mf/private true}
  [{:keys [width height title] :rest props}]
  (let [class (stl/css :loader)
        props (mf/spread-props props {:viewBox "0 0 677.34762 182.15429"
                                      :role "status"
                                      :width width
                                      :height height
                                      :class class})]
    [:> :svg props
     [:title title]
     [:g
      [:path {:d svg:loader-path-1}]
      [:path {:class (stl/css :loader-line)
              :d svg:loader-path-2}]]]))

(def ^:private schema:loader
  [:map
   [:class {:optional true} :string]
   [:width {:optional true} :int]
   [:height {:optional true} :int]
   [:title {:optional true} :string]
   [:overlay {:optional true} :boolean]
   [:file-loading {:optional true} :boolean]])

(mf/defc loader*
  {::mf/schema schema:loader}
  [{:keys [class width height title overlay children file-loading] :rest props}]
  (let [width  (or width (when (some? height) (mth/ceil (* height (/ 100 27)))) 100)
        height (or height (when (some? width) (mth/ceil (* width (/ 27 100)))) 27)

        class  (dm/str (or class "") " "
                       (stl/css-case :wrapper true
                                     :wrapper-overlay overlay
                                     :file-loading file-loading))

        title  (or title (tr "labels.loading"))

        tip*   (mf/use-state nil)
        tip    (deref tip*)]

    (mf/with-effect [file-loading]
      (when file-loading
        (let [sub (->> (rx/timer 1000 4000)
                       (rx/subs! (fn []
                                   (let [tip (rand-nth tips)]
                                     (reset! tip* [(tr (str (first tip) "." (second tip)))
                                                   (tr (str (first tip) "." (nth tip 2)))])))))]
          (partial rx/dispose! sub))))

    [:> "div" {:class class}
     [:div {:class (stl/css :loader-content)}
      [:> loader-icon* {:title title
                        :width width
                        :height height}]
      (when (and file-loading tip)
        [:div {:class (stl/css :tips-container)}
         [:div {:class (stl/css :tip-title)}
          (first tip)]
         [:div {:class (stl/css :tip-message)}
          (second tip)]])]
     children]))
