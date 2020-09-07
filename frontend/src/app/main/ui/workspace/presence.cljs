;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.workspace.presence
  (:require
   [rumext.alpha :as mf]
   [beicon.core :as rx]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.util.time :as dt]
   [app.util.timers :as tm]
   [app.util.router :as rt]))

(def pointer-icon-path
  (str "M5.292 4.027L1.524.26l-.05-.01L0 0l.258 1.524 3.769 3.768zm-.45 "
       "0l-.313.314L1.139.95l.314-.314zm-.5.5l-.315.316-3.39-3.39.315-.315 "
       "3.39 3.39zM1.192.526l-.668.667L.431.646.64.43l.552.094z"))

(mf/defc session-cursor
  [{:keys [session] :as props}]
  (let [point     (:point session)
        color     (:color session "#000000")
        transform (str "translate(" (:x point) "," (:y point) ") scale(4)")]
    [:g.multiuser-cursor {:transform transform}
     [:path {:fill color
             :d pointer-icon-path
             :font-family "sans-serif"}]
     [:g {:transform "translate(0 -291.708)"}
      [:rect {:width "21.415"
              :height "5.292"
              :x "6.849"
              :y "291.755"
              :fill color
              :fill-opacity ".893"
              :paint-order "stroke fill markers"
              :rx ".794"
              :ry ".794"}]
      [:text {:x "9.811"
              :y "295.216"
              :fill "#fff"
              :stroke-width ".265"
              :font-family "Open Sans"
              :font-size"2.91"
              :font-weight "400"
              :letter-spacing"0"
              :style {:line-height "1.25"}
              :word-spacing "0"}
       (:fullname session)]]]))

(mf/defc active-cursors
  {::mf/wrap [mf/memo]}
  [{:keys [page-id] :as props}]
  (let [counter  (mf/use-state 0)
        sessions (mf/deref refs/workspace-presence)
        sessions (->> (vals sessions)
                      (filter #(= page-id (:page-id %)))
                      (filter #(>= 3000 (- (inst-ms (dt/now)) (inst-ms (:updated-at %))))))]
    (mf/use-effect
     nil
     (fn []
       (let [sem (tm/schedule 1000 #(swap! counter inc))]
         (fn [] (rx/dispose! sem)))))

    (for [session sessions]
      (when (:point session)
        [:& session-cursor {:session session :key (:id session)}]))))

(mf/defc session-widget
  [{:keys [session self?] :as props}]
  (let [photo (:photo-uri session "/images/avatar.jpg")]
    [:li.tooltip.tooltip-bottom
     {:alt (:fullname session)
      :on-click (when self?
                  #(st/emit! (rt/navigate :settings/profile)))}
     [:img {:style {:border-color (:color session)}
            :src photo}]]))

(mf/defc active-sessions
  {::mf/wrap [mf/memo]}
  []
  (let [profile  (mf/deref refs/profile)
        sessions (mf/deref refs/workspace-presence)]
    [:ul.active-users
     (for [session (vals sessions)]
       [:& session-widget
        {:session session
         :self? (= (:id session) (:id profile))
         :key (:id session)}])]))


