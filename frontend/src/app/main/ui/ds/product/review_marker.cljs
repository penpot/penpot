;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.ds.product.review-marker
  (:require-macros
   [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.main.ui.ds.foundations.assets.icon :refer [icon*] :as i]
   [rumext.v2 :as mf]))

(def ^:private schema:review-marker
  [:map
   [:class {:optional true} [:maybe :string]]
   [:type {:optional true} [:maybe [:enum "comment" "task"]]]
   [:state {:optional true} [:maybe [:enum "default" "active" "resolved" "lost" "ghost"]]]
   [:label {:optional true} [:maybe :string]]
   [:counter {:optional true} [:maybe :int]]
   [:aria-label :string]])

(mf/defc review-marker*
  "The mark a design review leaves on the canvas. A comment is a round
  mark with its number; a task, a square one with the task icon. Active
  is the one open now, resolved a comment already settled (it shows a
  tick instead of its number), lost a mark whose layer was deleted and
  ghost what stays where a group of marks fanned out from. `counter` is
  the number of items a mark holds; a comment mark that holds several has
  no number, so it shows the comments icon. It renders a button: the caller names
  it (`aria-label`) and handles the click."
  {::mf/schema schema:review-marker}
  [{:keys [class type state label counter] :rest props}]
  (let [type  (d/nilv type "comment")
        state (d/nilv state "default")
        props (mf/spread-props props
                               {:class [class (stl/css-case :review-marker true
                                                            :is-task (= type "task")
                                                            :is-active (= state "active")
                                                            :is-resolved (= state "resolved")
                                                            :is-lost (= state "lost")
                                                            :is-ghost (= state "ghost"))]
                                :type "button"})]
    [:> :button props
     (cond
       (= type "task")
       [:> icon* {:icon-id i/view-as-list :size "s"}]

       (= state "resolved")
       [:> icon* {:icon-id i/tick :size "s"}]

       (nil? label)
       [:> icon* {:icon-id i/comments :size "s"}]

       :else
       label)
     (when (some? counter)
       [:span {:class (stl/css :counter)} counter])]))
