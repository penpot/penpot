;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns exporter.tmpdir
  "The temp-file area as a system component (`:exporter/tmpdir`).

  The legacy `app.util.shell` creates its `tmpdir` at namespace load:
  config read, log line and `mkdirSync` the first time anything
  requires it, in every thread including render workers and tests.
  This component pulls that moment into the lifecycle: referencing the
  var forces the creation during boot (fail fast on a bad `:tempdir`),
  orders it before anything that writes temp files, and carries the
  path in the instance for the day the temp area becomes injected
  instead of global. Named for what it owns, not for the legacy
  namespace it shadows."
  (:require
   [app.util.shell :as sh]
   [exporter.utils.system :as system]))

(defmethod system/init-key :exporter/tmpdir
  [_ _]
  {:tmpdir sh/tmpdir})
