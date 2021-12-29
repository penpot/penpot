;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.context
  (:require
   [rumext.alpha :as mf]))

(def render-ctx (mf/create-context nil))
(def def-ctx (mf/create-context false))

;; This content is used to replace complex colors to simple ones
;; for text shapes in the export process
(def text-plain-colors-ctx (mf/create-context false))

(def current-route      (mf/create-context nil))
(def current-profile    (mf/create-context nil))
(def current-team-id    (mf/create-context nil))
(def current-project-id (mf/create-context nil))
(def current-page-id    (mf/create-context nil))
(def current-file-id    (mf/create-context nil))
