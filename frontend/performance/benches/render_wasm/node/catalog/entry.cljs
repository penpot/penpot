;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns benches.render-wasm.node.catalog.entry
  "Node entry points of a compiled renderer benchmark build.

  The `:bench-render-wasm-catalog` build in `frontend/shadow-cljs.edn` names
  this namespace as its compilation entry through `:exports`, which exposes
  `collect-cases` to Node as `collectCases` and `build-identity` as
  `buildIdentity`. Both functions use Transit-encoded strings at the Node
  boundary.

  Requiring this namespace loads the benchmark scene declarations. Each build's
  `cases.cjs` artifact has its own scene registry and compiled identity; load
  the artifact of the build being queried."
  (:require
   [app.common.transit :as transit]
   [benches.render-wasm.cases :as cases]
   [benches.render-wasm.codec :as codec]
   [benches.render-wasm.identity :as identity]))

(defn collect-cases
  "Collects the benchmark cases selected from this build.

  Args:
    encoded-selection: A Transit-encoded map. The required :master-seed is an
      integer from 0 through 4294967295 used to derive the seed of each case.
      The optional :filter is a substring; a blank value selects all cases,
      otherwise only case ids that contain it are collected.

  Returns:
    A Transit-encoded map with :build-id, the identity compiled into this build,
    and :cases, a non-empty vector of validated case descriptors.
    Each descriptor has the case's :id and :scene, a :scene-description, its
    :params and :scene-seed, and the execution settings used by the runner.

  Throws:
    When the selection cannot be decoded, :master-seed is missing or not in
    0 through 4294967295, no cases are selected, or a registered scene or case
    fails validation. Case collection failures carry a :type in their
    exception data."
  [encoded-selection]
  (codec/encode-str
   {:build-id identity/build-id
    :cases (cases/collect-cases (transit/decode-str encoded-selection))}))

(defn build-identity
  "Returns the identity compiled into this build.

  Returns:
    A Transit-encoded string. Decoding it yields a map with :build-id, the
    identifier compiled into this artifact, defaulting to `unprepared`, and
    :cljs-profile-marks, a boolean that records whether renderer profiling
    marks were enabled at compile time."
  []
  (codec/encode-str (identity/data)))
