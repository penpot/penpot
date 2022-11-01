;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.files.features)

;; A set of enabled by default file features. Will be used in feature
;; negotiation on obtaining files from backend.

(def enabled #{})

(def ^:dynamic *previous* #{})
(def ^:dynamic *current* #{})
(def ^:dynamic *wrap-with-objects-map-fn* identity)
(def ^:dynamic *wrap-with-pointer-map-fn* identity)
