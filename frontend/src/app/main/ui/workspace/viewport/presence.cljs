;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.viewport.presence
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.common.time :as ct]
   [app.main.refs :as refs]
   [app.util.timers :as ts]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(def pointer-path
  (dm/str "M11.58,-0.47L11.47,-0.35L0.34,10.77L0.30,10.96L-0.46,"
          "15.52L4.29,14.72L15.53,3.47L11.58,-0.47ZL11.58,"
          "-0.47ZL11.58,-0.47ZM11.58,1.3C12.31,2.05,13.02,"
          "2.742,13.76,3.47L4.0053,13.23C3.27,12.50,2.55,"
          "11.78,1.82,11.05L11.58,1.30ZL11.58,1.30ZM1.37,12.15L2.90,"
          "13.68L1.67,13.89L1.165,13.39L1.37,12.15ZL1.37,12.15Z"))

(mf/defc session-cursor
  {::mf/props :obj
   ::mf/memo true}
  [{:keys [session profile zoom]}]
  (let [point     (:point session)
        bg-color  (:color session)
        fg-color  "var(--app-white)"
        transform (str/ffmt "translate(%, %) scale(%)"
                            (dm/get-prop point :x)
                            (dm/get-prop point :y)
                            (/ 1 zoom))


        fullname  (:fullname profile)
        fullname  (if (> (count fullname) 16)
                    (dm/str (str/slice fullname 0 12) "...")
                    fullname)]

    [:g {:class (stl/css :multiuser-cursor) :transform transform}
     [:path {:fill bg-color :d pointer-path}]
     [:g {:transform "translate(17 -10)"}
      [:foreignObject {:x -0.3
                       :y -12.5
                       :width 300
                       :height 120}
       [:div {:class (stl/css :profile-name)
              :style {:background-color bg-color :color fg-color}}
        fullname]]]]))

(mf/defc active-cursors
  {::mf/props :obj}
  [{:keys [page-id]}]
  (let [counter  (mf/use-state 0)
        profiles (mf/deref refs/profiles)
        sessions (mf/deref refs/workspace-presence)
        zoom     (mf/deref refs/selected-zoom)

        sessions (->> (vals sessions)
                      (filter :point)
                      (filter #(= page-id (:page-id %)))
                      (filter #(>= 5000 (- (inst-ms (ct/now))
                                           (inst-ms (:updated-at %))))))]
    (mf/with-effect nil
      (let [sem (ts/schedule 1000 #(swap! counter inc))]
        (fn [] (rx/dispose! sem))))

    (for [session sessions]
      [:& session-cursor
       {:session session
        :zoom zoom
        :profile (get profiles (:profile-id session))
        :key (dm/str (:id session))}])))
