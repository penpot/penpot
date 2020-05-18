;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.main.ui.workspace.sidebar.options.page
  "Page options menu entries."
  (:require
   [rumext.alpha :as mf]
   [okulary.core :as l]
   [uxbox.main.refs :as refs]))

(def options-iref
  (l/derived :options refs/workspace-data))

(mf/defc options
  ;; TODO: Define properties for page
  [{:keys [page] :as props}])

