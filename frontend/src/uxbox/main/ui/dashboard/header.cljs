;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2015-2020 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2020 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.dashboard.header
  (:require
   [rumext.alpha :as mf]
   [uxbox.util.i18n :as i18n :refer [t]]
   [uxbox.main.ui.dashboard.profile :refer [profile-section]]))


;; --- Component: Header

(mf/defc header
  [{:keys [profile] :as props}]
  (let [locale (i18n/use-locale)]
    [:header#main-bar.main-bar
     [:h1.dashboard-title "Personal"]
     [:a.btn-dashboard "+ New project"]]))

