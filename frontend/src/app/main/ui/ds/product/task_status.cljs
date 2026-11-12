;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.ds.product.task-status
  (:require-macros
   [app.main.style :as stl])
  (:require
   [rumext.v2 :as mf]))

(def statuses
  #{"open" "done" "reopened" "verified" "wont-do"})

(def ^:private schema:task-status
  [:map
   [:class {:optional true} [:maybe :string]]
   [:status (into [:enum] statuses)]
   [:label :string]])

(mf/defc task-status*
  "The state of a task, a word in the colour of the state: open is blue,
  done green, reopened orange, verified purple and won't do red."
  {::mf/schema schema:task-status}
  [{:keys [class status label] :rest props}]
  (let [props (mf/spread-props props
                               {:class [class (stl/css-case :task-status true
                                                            :is-open (= status "open")
                                                            :is-done (= status "done")
                                                            :is-reopened (= status "reopened")
                                                            :is-verified (= status "verified")
                                                            :is-wont-do (= status "wont-do"))]})]
    [:> :span props label]))
