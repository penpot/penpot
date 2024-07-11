;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.ds.foundations.typography)

(def ^:typography-id display "display")
(def ^:typography-id title-large "title-large")
(def ^:typography-id title-medium "title-medium")
(def ^:typography-id title-small "title-small")
(def ^:typography-id headline-large "headline-large")
(def ^:typography-id headline-medium "headline-medium")
(def ^:typography-id headline-small "headline-small")
(def ^:typography-id body-large "body-large")
(def ^:typography-id body-medium "body-medium")
(def ^:typography-id body-small "body-small")
(def ^:typography-id code-font "code-font")

(def typography-list #{display
                       title-large
                       title-medium
                       title-small
                       headline-large
                       headline-medium
                       headline-small
                       body-large
                       body-medium
                       body-small
                       code-font})
