;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.notifications.badge
  (:require-macros [app.main.style :as stl])
  (:require
   [rumext.v2 :as mf]))

(mf/defc badge-notification
  "They are persistent, informative and non-actionable.
   They are small messages in specific areas off the app"

  {::mf/props :obj}
  [{:keys [type content size is-focus] :as props}]

  [:aside {:class (stl/css-case :badge-notification true
                                :warning      (= type :warning)
                                :error        (= type :error)
                                :success      (= type :success)
                                :info         (= type :info)
                                :small        (= size :small)
                                :focus        is-focus)}
   content])

