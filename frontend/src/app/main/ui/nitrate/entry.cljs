;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.nitrate.entry
  (:require
   [app.main.data.auth :as da]
   [app.main.data.nitrate :as dnt]
   [app.main.router :as rt]
   [app.main.store :as st]
   [app.main.ui.ds.product.loader :refer [loader*]]
   [app.util.i18n :refer [tr]]
   [rumext.v2 :as mf]))

(mf/defc nitrate-entry*
  {::mf/private true}
  [{:keys [profile]}]
  (mf/with-effect [profile]
    (dnt/activate-nitrate-entry-popup!)
    (if (da/is-authenticated? profile)
      (st/emit! (rt/nav :dashboard-recent {:team-id (:default-team-id profile)}))
      (st/emit! (rt/nav :auth-register))))

  [:> loader* {:title (tr "labels.loading")
               :overlay true}])

(mf/defc nitrate-entry-page*
  [props]
  [:> nitrate-entry* props])
