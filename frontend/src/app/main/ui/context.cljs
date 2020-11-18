;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.context
  (:require
   [rumext.alpha :as mf]))

(def embed-ctx (mf/create-context false))
(def render-ctx (mf/create-context nil))

(def current-route (mf/create-context nil))
(def current-team-id (mf/create-context nil))
(def current-project-id (mf/create-context nil))
(def current-page-id (mf/create-context nil))
(def current-file-id (mf/create-context nil))
