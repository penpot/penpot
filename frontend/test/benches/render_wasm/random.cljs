;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns benches.render-wasm.random
  "Seeded numbers for reproducible scene construction and report resampling.

  A seed is an integer from 0 through 4294967295. Each call to `create` owns a
  separate Mulberry32 state. Calling the returned function advances that state
  and returns a number in [0,1). Equal seeds and equal numbers of calls produce
  equal sequences, including seed zero."
  (:require
   [app.common.types.color :as clr]
   [app.common.uuid :as uuid]))


(defn seed?
  "Returns true for integers from 0 through 4294967295, inclusive."
  [value]
  (and (integer? value) (<= 0 value 4294967295)))

(defn create
  "Returns a function that draws seeded numbers from [0, 1) using Mulberry32.
  Each function owns its state. A seed outside the uint32 range throws
  ::invalid-seed; uint32 means an integer from 0 through 4294967295."
  [seed]
  (when-not (seed? seed)
    (throw (ex-info "random seed must be an integer in [0, 2^32)"
                    {:type ::invalid-seed :seed seed})))
  (let [state (volatile! (bit-or seed 0))]
    (fn []
      (let [s (bit-or (+ @state 0x6d2b79f5) 0)
            _ (vreset! state s)
            v (js/Math.imul (bit-xor s (unsigned-bit-shift-right s 15))
                            (bit-or s 1))
            v (bit-xor v (+ v (js/Math.imul (bit-xor v (unsigned-bit-shift-right v 7))
                                            (bit-or v 61))))
            v (bit-xor v (unsigned-bit-shift-right v 14))]
        (/ (unsigned-bit-shift-right v 0) 4294967296)))))

(defn round3
  "Rounds to 3 decimal places"
  [value]
  (/ (js/Math.round (* 1000 value)) 1000))


(defn rng-float
  "Draws a float in `[min, max)` from the random source."
  [rng min max]
  (+ min (* (rng) (- max min))))

(defn rng-int
  "Draws an integer in `[min, max)` from the random source."
  [rng min max]
  (js/Math.floor (rng-float rng min max)))

(defn rng-uuid
  "Draws a deterministic uuid from two 32-bit values of the random source."
  [rng]
  (uuid/custom (rng-int rng 0 4294967296)
               (rng-int rng 0 4294967296)))

(defn rng-hex-color
  "Draws a hex color from three RGB channels in [0,256)."
  [rng]
  (clr/rgb->hex [(rng-int rng 0 256) (rng-int rng 0 256) (rng-int rng 0 256)]))

(defn rng-opacity
  "Draws opacity in [0.1,0.999], rounded to three decimal places."
  [rng]
  (min 0.999 (round3 (rng-float rng 0.1 1))))
