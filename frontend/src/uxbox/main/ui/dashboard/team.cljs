;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2015-2020 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2020 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.dashboard.team
  (:refer-clojure :exclude [sort-by])
  (:require
   [cuerdas.core :as str]
   [lentes.core :as l]
   [rumext.alpha :as mf]
   [uxbox.builtins.icons :as i]
   [uxbox.common.exceptions :as ex]
   [uxbox.main.constants :as c]
   [uxbox.main.data.projects :as udp]
   [uxbox.main.data.dashboard :as dsh]
   [uxbox.main.store :as st]
   [uxbox.main.exports :as exports]
   [uxbox.main.refs :as refs]
   [uxbox.main.ui.modal :as modal]
   [uxbox.main.ui.keyboard :as kbd]
   [uxbox.main.ui.confirm :refer [confirm-dialog]]
   [uxbox.util.dom :as dom]
   [uxbox.util.i18n :as i18n :refer [t tr]]
   [uxbox.util.router :as rt]
   [uxbox.util.time :as dt]))

;; --- Component: Content

;; (def files-ref
;;   (-> (comp (l/key :files)
;;             (l/lens vals))
;;       (l/derive st/state)))

;; (def opts-iref
;;   (-> (l/key :dashboard-projects)
;;       (l/derive st/state)))

;; --- Component: Drafts Page

(mf/defc team-page
  [{:keys [section team-id] :as props}]
  (mf/use-effect
   {:fn #(st/emit! (dsh/initialize-team team-id))
    :deps (mf/deps team-id)})

  [:section.dashboard-grid.library
   [:p "TODO"]])
