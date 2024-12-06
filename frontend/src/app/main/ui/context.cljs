;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.context
  (:require
   [rumext.v2 :as mf]))

(def render-id            (mf/create-context nil))

(def current-route        (mf/create-context nil))
(def current-profile      (mf/create-context nil))
(def current-team-id      (mf/create-context nil))
(def current-project-id   (mf/create-context nil))
(def current-page-id      (mf/create-context nil))
(def current-file-id      (mf/create-context nil))
(def current-vbox         (mf/create-context nil))
(def current-svg-root-id  (mf/create-context nil))

(def active-frames        (mf/create-context nil))
(def render-thumbnails    (mf/create-context nil))

(def libraries            (mf/create-context nil))
(def components-v2        (mf/create-context nil))
(def design-tokens        (mf/create-context nil))

(def current-scroll       (mf/create-context nil))
(def current-zoom         (mf/create-context nil))

(def workspace-read-only? (mf/create-context nil))
(def is-component?        (mf/create-context false))
(def sidebar              (mf/create-context nil))

(def permissions          (mf/create-context nil))
