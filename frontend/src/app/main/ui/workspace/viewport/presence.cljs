;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.viewport.presence
  (:require
   [app.main.refs :as refs]
   [app.util.time :as dt]
   [app.util.timers :as ts]
   [beicon.core :as rx]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(def pointer-icon-path
  (str "M11.58,-0.47L11.47,-0.35L0.34,10.77L0.30,10.96L-0.46,"
       "15.52L4.29,14.72L15.53,3.47L11.58,-0.47ZL11.58,"
       "-0.47ZL11.58,-0.47ZM11.58,1.3C12.31,2.05,13.02,"
       "2.742,13.76,3.47L4.0053,13.23C3.27,12.50,2.55,"
       "11.78,1.82,11.05L11.58,1.30ZL11.58,1.30ZM1.37,12.15L2.90,"
       "13.68L1.67,13.89L1.165,13.39L1.37,12.15ZL1.37,12.15Z"))

(mf/defc session-cursor
  [{:keys [session profile] :as props}]
  (let [zoom             (mf/deref refs/selected-zoom)
        point            (:point session)
        background-color (:color session "var(--color-black)")
        text-color       (:text-color session "var(--color-white)")
        transform        (str/fmt "translate(%s, %s) scale(%s)" (:x point) (:y point) (/ 1 zoom))
        shown-name       (if (> (count (:fullname profile)) 16)
                           (str (str/slice (:fullname profile) 0 12) "...")
                           (:fullname profile))]
    [:g.multiuser-cursor {:transform transform}
     [:path {:fill background-color
             :d pointer-icon-path}]
     [:g {:transform "translate(17 -10)"}
      [:foreignObject {:x -0.3
                       :y -12.5
                       :width 300
                       :height 120}
       [:div.profile-name {:style {:background-color background-color
                                   :color text-color}}
        shown-name]]]]))

(mf/defc active-cursors
  {::mf/wrap [mf/memo]}
  [{:keys [page-id] :as props}]
  (let [counter  (mf/use-state 0)
        users    (mf/deref refs/users)
        sessions (mf/deref refs/workspace-presence)
        sessions (->> (vals sessions)
                      (filter #(= page-id (:page-id %)))
                      (filter #(>= 5000 (- (inst-ms (dt/now)) (inst-ms (:updated-at %))))))]
    (mf/use-effect
     nil
     (fn []
       (let [sem (ts/schedule 1000 #(swap! counter inc))]
         (fn [] (rx/dispose! sem)))))

    (for [session sessions]
      (when (:point session)
        [:& session-cursor {:session session
                            :profile (get users (:profile-id session))
                            :key (:id session)}]))))





