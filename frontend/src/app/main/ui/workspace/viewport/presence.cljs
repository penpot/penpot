;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.workspace.viewport.presence
  (:require
   [app.main.refs :as refs]
   [app.util.time :as dt]
   [app.util.timers :as ts]
   [beicon.core :as rx]
   [cuerdas.core :as str]
   [rumext.alpha :as mf]))

(def pointer-icon-path
  (str "M5.292 4.027L1.524.26l-.05-.01L0 0l.258 1.524 3.769 3.768zm-.45 "
       "0l-.313.314L1.139.95l.314-.314zm-.5.5l-.315.316-3.39-3.39.315-.315 "
       "3.39 3.39zM1.192.526l-.668.667L.431.646.64.43l.552.094z"))

(mf/defc session-cursor
  [{:keys [session profile] :as props}]
  (let [zoom      (mf/deref refs/selected-zoom)
        point     (:point session)
        color     (:color session "var(--color-black)")
        transform (str/fmt "translate(%s, %s) scale(%s)" (:x point) (:y point) (/ 4 zoom))]
    [:g.multiuser-cursor {:transform transform}
     [:path {:fill color
             :d pointer-icon-path
             }]
     [:g {:transform "translate(0 -291.708)"}
      [:rect {:width 25
              :height 5
              :x 7
              :y 291.5
              :fill color
              :fill-opacity 0.8
              :paint-order "stroke fill markers"
              :rx 1
              :ry 1}]
      [:text {:x 8
              :y 295
              :width 25
              :height 5
              :overflow "hidden"
              :fill "var(--color-white)"
              :stroke-width 1
              :font-family "Works Sans"
              :font-size 3
              :font-weight 400
              :letter-spacing 0
              :style { :line-height 1.25 }
              :word-spacing 0}
       (str (str/slice (:fullname profile) 0 14)
            (when (> (count (:fullname profile)) 14) "..."))]]]))

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





