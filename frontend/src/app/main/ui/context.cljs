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
(def design-tokens        (mf/create-context nil))

(def current-scroll       (mf/create-context nil))
(def current-zoom         (mf/create-context nil))

(def workspace-read-only? (mf/create-context nil))
(def is-component?        (mf/create-context false))

(def sidebar
  "A context that intends to store the current sidebar position,
  usefull for components that behaves distinctly if they are showed in
  right sidebar or left sidebar.

  Possible values: `:right:` and `:left`."
  (mf/create-context nil))

(def permissions          (mf/create-context nil))
(def can-edit?            (mf/create-context nil))

(def active-tokens-by-type
  "Active tokens by type, used mainly for provide tokens data to the
  right sidebar menu options components."
  (mf/create-context nil))

