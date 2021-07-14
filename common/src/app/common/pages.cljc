;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.common.pages
  "A common (clj/cljs) functions and specs for pages."
  (:require
   [app.common.data :as d]
   [app.common.pages.changes :as changes]
   [app.common.pages.common :as common]
   [app.common.pages.helpers :as helpers]
   [app.common.pages.indices :as indices]
   [app.common.pages.init :as init]
   [app.common.pages.spec :as spec]
   [clojure.spec.alpha :as s]))

;; Common
(d/export common/root)
(d/export common/file-version)
(d/export common/default-color)
(d/export common/component-sync-attrs)

;; Helpers

(d/export helpers/walk-pages)
(d/export helpers/select-objects)
(d/export helpers/update-object-list)
(d/export helpers/get-component-shape)
(d/export helpers/get-root-shape)
(d/export helpers/make-container)
(d/export helpers/page?)
(d/export helpers/component?)
(d/export helpers/get-container)
(d/export helpers/get-shape)
(d/export helpers/get-component)
(d/export helpers/is-main-of)
(d/export helpers/get-component-root)
(d/export helpers/get-children)
(d/export helpers/get-children-objects)
(d/export helpers/get-object-with-children)
(d/export helpers/is-shape-grouped)
(d/export helpers/get-parent)
(d/export helpers/get-parents)
(d/export helpers/clean-loops)
(d/export helpers/calculate-invalid-targets)
(d/export helpers/valid-frame-target)
(d/export helpers/position-on-parent)
(d/export helpers/insert-at-index)
(d/export helpers/append-at-the-end)
(d/export helpers/select-toplevel-shapes)
(d/export helpers/select-frames)
(d/export helpers/clone-object)
(d/export helpers/indexed-shapes)
(d/export helpers/expand-region-selection)
(d/export helpers/frame-id-by-position)
(d/export helpers/set-touched-group)
(d/export helpers/touched-group?)
(d/export helpers/get-base-shape)
(d/export helpers/is-parent?)
(d/export helpers/get-index-in-parent)
(d/export helpers/split-path)
(d/export helpers/join-path)
(d/export helpers/parse-path-name)
(d/export helpers/merge-path-item)
(d/export helpers/compact-path)
(d/export helpers/compact-name)

;; Indices
(d/export indices/calculate-z-index)
(d/export indices/update-z-index)
(d/export indices/generate-child-all-parents-index)
(d/export indices/generate-child-parent-index)
(d/export indices/create-mask-index)

;; Process changes
(d/export changes/process-changes)

;; Initialization
(d/export init/default-frame-attrs)
(d/export init/default-shape-attrs)
(d/export init/make-file-data)
(d/export init/make-minimal-shape)
(d/export init/make-minimal-group)
(d/export init/empty-file-data)

;; Specs

(s/def ::changes ::spec/changes)
(s/def ::color ::spec/color)
(s/def ::data ::spec/data)
(s/def ::media-object ::spec/media-object)
(s/def ::minimal-shape ::spec/minimal-shape)
(s/def ::page ::spec/page)
(s/def ::recent-color ::spec/recent-color)
(s/def ::shape-attrs ::spec/shape-attrs)
(s/def ::typography ::spec/typography)

