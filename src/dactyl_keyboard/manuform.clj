(ns dactyl-keyboard.manuform
  (:refer-clojure :exclude [use import])
  (:require [clojure.core.matrix :refer [array matrix mmul]]
            [scad-clj.scad :refer :all]
            [scad-clj.model :refer :all]
            [dactyl-keyboard.util :refer :all]
            [dactyl-keyboard.common :refer :all]))

(def column-style :standard)

; This has been manually changed to produce the stagger required for each finger
(defn column-offset
  "Determines how much 'stagger' the columns are
   0 = inner index finger's column.
   1 = index finger's column.
   2 = middle finger's column.
   3 = ring finger's column.
   4 >= pinky finger's column.
   [x y z] means that it will be staggered by 'x'mm in X axis (left/right),
   'y'mm in Y axis (front/back), and 'z'mm in Z axis (up/down). "
  [ortho? column]
  (if ortho?
    (cond (= column 2)  [0   0    -6.5]
          (>= column 4) [0   0     6]
          :else         [0   0     0])
    (cond (= column 0)  [0   -7     0]			; Inside index finger
    	  (= column 1)  [0   -5     0]			; Index finger
    	  (= column 2)  [0   2.82 -6.5]			; middle finger
          (= column 3)	[0   -5	   0]			; ring finger
          (= column 4)	[0  -15    6]			; pinky finger
          (= column 5)	[0  -17    6]			; outer pinky finger
          :else         [0   0     0])))		; just incase

; it dictates the location of the thumb cluster.
; the first member of the vector is x axis, second one y axis,
; while the last one is y axis.
; the higher x axis value is, the closer it to the pinky.
; the higher y axis value is, the closer it to the alphas.
; the higher z axis value is, the higher it is.
(def thumb-offsets [6 -3 7])

; controls overall height; original=9 with centercol=3; use 16 for centercol=2
;(def keyboard-z-offset 4)

; length of the first downward-sloping part of the wall (negative)
(def wall-z-offset -15)
; offset in the x and/or y direction for the first downward-sloping part of the wall (negative)
(def wall-xy-offset 5)
; wall thickness parameter; originally 5
(def wall-thickness 2)

;; Settings for column-style == :fixed 
;; The defaults roughly match Maltron settings
;;   http://patentimages.storage.googleapis.com/EP0219944A2/imgf0002.png
;; Fixed-z overrides the z portion of the column ofsets above.
;; NOTE: THIS DOESN'T WORK QUITE LIKE I'D HOPED.
(def fixed-angles [(deg2rad 10) (deg2rad 10) 0 0 0 (deg2rad -15) (deg2rad -15)])
(def fixed-x [-41.5 -22.5 0 20.3 41.4 65.5 89.6])  ; relative to the middle finger
(def fixed-z [12.1    8.3 0  5   10.7 14.5 17.5])
(def fixed-tenting (deg2rad 0))

;;;;;;;;;;;;;;;;;;;;;;;
;; General variables ;;
;;;;;;;;;;;;;;;;;;;;;;;

(defn fcenterrow
  "Determines where should the center (bottom-most point in the row's curve)
   of the row located at. And most people would want to have the center
   at the homerow. Why does it subtract the value by 3? Because this codebase
   starts the row from the higher row (F row -> num row -> top row)
   and the homerow is number 3 from the last after thumb and bottom row."
  [nrows]
  (- nrows 3))

(defn flastrow
  "Determines where the last row should be located at."
  [nrows]
  (- nrows 1))
(defn fcornerrow
  "Determines where the penultimate row should be located at."
  [nrows]
  (- nrows 2))
(defn fmiddlerow
  "Should be replaced with `fcenterrow`."
  [nrows]
  (- nrows 3))
(defn flastcol
  "Determines where the last column should be located at. With 0 being inner index
   finger, 1 being index finger, and so on."
  [ncols]
  (- ncols 1))

;;;;;;;;;;;;;;;;;;;;;;;;;
;; Placement Functions ;;
;;;;;;;;;;;;;;;;;;;;;;;;;

; an array of columns from 0 to number of columns.
(defn columns
  "It creates an array for column placement. Where 0 being inner index
   finger's column, 1 being index finger's column, 2 middle finger's, and so on."
  [ncols]
  (range 0 ncols))
(defn inner-columns
  "It creates an array for column placement. Where -1 being inner-inner index
   finger's column, 1 being index finger's column, 2 middle finger's, and so on."
  [ncols]
  (range -1 ncols))
(defn rows
  "It creates an array for row placement. Where 0 being top-most row, 1 second
   top-most row, and so on."
  [nrows]
  (range 0 nrows))
(defn inner-rows
  "It creates an array for row placement for the inner-most column. Where 0 being
   top-most row, 1 second top-most row, and so on."
  [nrows]
  (range 0 (fcornerrow nrows)))

(defn column-x-delta
  [beta]
  (+ -1 (- (* fcolumn-radius (Math/sin beta)))))
(defn column-base-angle
  [beta centercol]
  (* beta (- centercol 2)))

; when set `use-wide-pinky?`,
; you will get 1.5u keys for the outermost pinky keys.
(defn offset-for-column
  "This function is used to give additional spacing for the column.
   Main use case is to make the outer pinky keys use 1.5u."
  [c col row]
  (let [use-wide-pinky? (get c :configuration-use-wide-pinky?)
        nrows (get c :configuration-nrows)
        ncols (get c :configuration-ncols)
        lastrow (flastrow nrows)
        lastcol (flastcol ncols)]
    (if (and use-wide-pinky?
             (not= row lastrow)
             (= col lastcol))
      5.5
      0)))

; this is the helper function to 'place' the keys on the defined curve
; of the board.
(defn apply-key-geometry
  "Helps to place the keys in the determined where a key should be placed
   and rotated in xyz coordinate based on its position (row and column).
   It is the implementation detail of `key-place`."
  [c translate-fn rotate-x-fn rotate-y-fn column row shape]
  (let [alpha (get c :configuration-alpha)
        beta (get c :configuration-beta)
        centercol (get c :configuration-centercol)
        centerrow (fcenterrow (get c :configuration-nrows))
        ortho? (get c :configuration-ortho?)
        tenting-angle (get c :configuration-tenting-angle)
        keyboard-z-offset (get c :configuration-keyboard-z-offset)
        column-angle (* beta (- centercol column))
        placed-shape (->> shape
                          (translate-fn [(offset-for-column c
                                                            column
                                                            row)
                                         0
                                         (- (frow-radius alpha))])
                          (rotate-x-fn  (* alpha (- centerrow row)))
                          (translate-fn [0 0 (frow-radius alpha)])
                          (translate-fn [0 0 (- (fcolumn-radius beta))])
                          (rotate-y-fn  column-angle)
                          (translate-fn [0 0 (fcolumn-radius beta)])
                          (translate-fn (column-offset ortho? column)))]
    (->> placed-shape
         (rotate-y-fn  tenting-angle)
         (translate-fn [0 0 keyboard-z-offset]))))

; this is the function that puts the key switch holes
; based on the row and the column.
(defn key-place
  "Puts the keys' shape to its place based on it's column and row."
  [c column row shape]
  (apply-key-geometry c
                      translate
                      (fn [angle obj] (rotate angle [1 0 0] obj))
                      (fn [angle obj] (rotate angle [0 1 0] obj))
                      column row shape))

(defn rotate-around-x [angle position]
  (mmul
   [[1 0 0]
    [0 (Math/cos angle) (- (Math/sin angle))]
    [0 (Math/sin angle)    (Math/cos angle)]]
   position))

(defn rotate-around-y [angle position]
  (mmul
   [[(Math/cos angle)     0 (Math/sin angle)]
    [0                    1 0]
    [(- (Math/sin angle)) 0 (Math/cos angle)]]
   position))

(defn key-position [c column row position]
  (apply-key-geometry c (partial map +) rotate-around-x rotate-around-y column row position))

(defn key-holes
  "Determines which keys should be generated based on the configuration."
  [c]
  (let [row-count (get c :configuration-last-row-count)
        ncols (get c :configuration-ncols)
        nrows (get c :configuration-nrows)
        lastrow (flastrow nrows)]
    (apply union
           (for [column (columns ncols)
                 row (rows nrows)
                 :when (case row-count
                         :zero (not= row lastrow)
                         :two (or (.contains [2 3] column)
                                  (not= row lastrow))
                         :full (or (not (.contains [0 1] column)) (not= row lastrow)))]
             (->> (single-plate c)
                  (key-place c column row))))))

(defn key-inner-place
  "It generates the placement of the inner column.
   TODO: genericisise it."
  [c column row shape]
  (apply-key-geometry c
                      translate
                      (fn [angle obj] (rotate angle [1 0 0] obj))
                      (fn [angle obj] (rotate angle [0 1 0] obj))
                      column row shape))

(defn inner-key-holes [c]
  (let [nrows (get c :configuration-nrows)]
    (apply union (for [row (inner-rows nrows)]
                   (->> (single-plate c)
                        (key-inner-place c -1 row))))))

(defn caps [c]
  (let [use-inner-column? (get c :configuration-use-inner-column?)
        row-count (get c :configuration-last-row-count)
        use-wide-pinky? (get c :configuration-use-wide-pinky?)
        ncols (get c :configuration-ncols)
        nrows (get c :configuration-nrows)
        lastrow (flastrow nrows)
        cornerrow (fcornerrow nrows)
        lastcol (flastcol ncols)]
    (apply
     union
     (for [column (if use-inner-column? (range -1 ncols) (columns ncols))
           row (rows nrows)
           :when (case row-count
                   :zero (not= row lastrow)
                   :two (or (.contains [2 3] column)
                            (not= row lastrow))
                   :full (or (not (.contains [0 1] column)) (not= row lastrow)))
           :when (if use-inner-column?
                   (not (and (.contains [-1] column)
                             (or (= row cornerrow)
                                 (= row lastrow))))
                   true)]
       (->> (sa-cap (if (and use-wide-pinky?
                             (= column lastcol)
                             (not= row lastrow))
                      1.5
                      1))
            (key-place c column row))))))

;;;;;;;;;;;;;;;;;;;;
;; Web Connectors ;;
;;;;;;;;;;;;;;;;;;;;

(defn wide-post-tr [use-wide-pinky?]
  (if use-wide-pinky?
    (translate [(- (/ mount-width  1.2) post-adj) (- (/ mount-height  2) post-adj) 0] web-post)
    web-post-tr))
(defn wide-post-tl [use-wide-pinky?]
  (if use-wide-pinky?
    (translate [(+ (/ mount-width -1.2) post-adj) (- (/ mount-height  2) post-adj) 0] web-post)
    web-post-tl))
(defn wide-post-bl [use-wide-pinky?]
  (if use-wide-pinky?
    (translate [(+ (/ mount-width -1.2) post-adj) (+ (/ mount-height -2) post-adj) 0] web-post)
    web-post-bl))
(defn wide-post-br [use-wide-pinky?]
  (if use-wide-pinky?
    (translate [(- (/ mount-width  1.2) post-adj) (+ (/ mount-height -2) post-adj) 0] web-post)
    web-post-br))

(defn connectors
  "It creates the wall which connects to each keys in the main body based
   on the configuration provided."
  [c]
  (let [use-inner-column? (get c :configuration-use-inner-column?)
        row-count (get c :configuration-last-row-count)
        ncols (get c :configuration-ncols)
        nrows (get c :configuration-nrows)
        lastrow (flastrow nrows)
        cornerrow (fcornerrow nrows)
        middlerow (fmiddlerow nrows)]
    (union
     (apply
      union
      (concat
      ;; Row connections
       (for [column (range (if use-inner-column? -1 0) (dec ncols))
             row (range 0 (inc lastrow))
             :when (case row-count
                     :zero (or (not= row lastrow)
                               (and (= row cornerrow)
                                    (= column -1)))
                     :two (or (.contains [2] column)
                              (not= row lastrow))
                     :full (not (and (= row lastrow)
                                     (.contains [-1 0 1] column))))]
         (triangle-hulls
          (key-place c (inc column) row web-post-tl)
          (key-place c column row web-post-tr)
          (key-place c (inc column) row web-post-bl)
          (if (not (and (= column -1)
                        (= row cornerrow)))
            (key-place c column row web-post-br)
            ())))

      ;; Column connections
       (for [column (if use-inner-column? (inner-columns ncols) (columns ncols))
             row (range 0 lastrow)
             :when (case row-count
                     :zero (not= row cornerrow)
                     :two (or (not= row cornerrow))
                     :full (not (and (= row cornerrow)
                                     (.contains [-1 0 1] column))))]
         (triangle-hulls
          (key-place c column row web-post-br)
          (key-place c column row web-post-bl)
          (key-place c column (inc row) web-post-tr)
          (if (not (and (= column -1)
                        (= row middlerow)))
            (key-place c column (inc row) web-post-tl)
            ())))

      ;; Diagonal connections
       (for [column (range (if use-inner-column? -1 0) (dec ncols))
             row (range 0 lastrow)
             :when (case row-count
                     :full (not (or (and (= row lastrow)
                                         (.contains [-1 0 1] column))
                                    (and (= row cornerrow)
                                         (.contains [-1 0 1] column))))
                     (or (not= row cornerrow)))]
         (triangle-hulls
          (key-place c column row web-post-br)
          (key-place c column (inc row) web-post-tr)
          (key-place c (inc column) row web-post-bl)
          (key-place c (inc column) (inc row) web-post-tl)))))
     (case row-count
       :two (triangle-hulls (key-place c 2 lastrow   web-post-tr)
                            (key-place c 3 cornerrow web-post-bl)
                            (key-place c 3 lastrow   web-post-tl)
                            (key-place c 3 cornerrow web-post-br)
                            (key-place c 3 lastrow   web-post-tr)
                            (key-place c 4 cornerrow web-post-bl)
                            (key-place c 3 lastrow   web-post-br))
       ()))))

;;;;;;;;;;;;
;; Thumbs ;;
;;;;;;;;;;;;

; this is where the original position of the thumb switches defined.
; each and every thumb keys is derived from this value.
; the value itself is defined from the 'm' key's position in qwerty layout
; and then added by some values, including thumb-offsets above.
(defn thumborigin [c]
  (let [cornerrow (fcornerrow (get c :configuration-nrows))]
    (map + (key-position c 1 cornerrow [(/ mount-width 2) (- (/ mount-height 2)) 0])
         thumb-offsets)))

(defn thumb-tr-place [configuration shape]
  (->> shape
       (rotate (deg2rad  10) [1 0 0])
       (rotate (deg2rad -53) [0 1 0])
       (rotate (deg2rad  10) [0 0 1])
       (translate (thumborigin configuration))
       (translate [-12 -16 9])))
(defn thumb-tl-place [c shape]
  (let [minidox-style? (get c :configuration-minidox-style?)
        movement (if minidox-style? [-22 -18 -8] )
        z-rotation (if minidox-style? 18 12)]
    (->> shape
         (rotate (deg2rad 5) [1 0 0])
         (rotate (deg2rad -64) [0 1 0])
         (rotate (deg2rad  z-rotation) [0 0 1])
         (translate (thumborigin c))
         (translate movement))))
(defn thumb-mr-place [c shape]
  (->> shape
       (rotate (deg2rad  -6) [1 0 0])
       (rotate (deg2rad -34) [0 1 0])
       (rotate (deg2rad  48) [0 0 1])
       (translate (thumborigin c))
       (translate [-29 -40 -13])))
(defn thumb-ml-place [c shape]
  (let [minidox-style? (get c :configuration-minidox-style?)
        movement (if minidox-style? [-28 -21 -26])]
    (->> shape
         (rotate (deg2rad   -2) [1 0 0])
         (rotate (deg2rad -73) [0 1 0])
         (rotate (deg2rad  25) [0 0 1])
         (translate (thumborigin c))
         (translate movement))))
(defn thumb-br-place [c shape]
  (->> shape
       (rotate (deg2rad -16) [1 0 0])
       (rotate (deg2rad -33) [0 1 0])
       (rotate (deg2rad  54) [0 0 1])
       (translate (thumborigin c))
       (translate [-37.8 -55.3 -25.3])))
(defn thumb-bl-place [c shape]
  (->> shape
       (rotate (deg2rad  -4) [1 0 0])
       (rotate (deg2rad -35) [0 1 0])
       (rotate (deg2rad  52) [0 0 1])
       (translate (thumborigin c))
       (translate [-56.3 -43.3 -23.5])))

(defn thumb-1x-layout [c shape]
  (let [minidox-style? (get c :configuration-minidox-style?)]
    (if-not minidox-style?
      (union
       (thumb-ml-place c shape)
       (thumb-mr-place c shape)
       (thumb-bl-place c shape)
       (thumb-br-place c shape))
      ())))

(defn thumb-15x-layout [c shape]
  (let [minidox-style? (get c :configuration-minidox-style?)]
    (union
     (if minidox-style? (thumb-ml-place c shape) ())
     (thumb-tr-place c shape)
     (thumb-tl-place c shape))))

(def larger-plate
  (let [plate-height (/ (- sa-double-length mount-height) 3)
        top-plate (->> (cube mount-width plate-height web-thickness)
                       (translate [0 (/ (+ plate-height mount-height) 2)
                                   (- plate-thickness (/ web-thickness 2))]))]
    (union top-plate (mirror [0 1 0] top-plate))))

(defn thumbcaps [c]
  (union
   (thumb-1x-layout c (sa-cap 1))
   (thumb-15x-layout c (rotate (/ pi 2) [0 0 1] (sa-cap 1.5)))))

(defn thumb [c]
  (union
   (thumb-1x-layout c  (single-plate c))
   (thumb-15x-layout c (rotate (/ pi 2) [0 0 1](single-plate c)))
   (thumb-15x-layout c larger-plate)))

(def thumb-post-tr
  (translate [(- (/ mount-width 2) post-adj)
              (- (/ mount-height  1.15) post-adj)
              0] web-post))
(def thumb-post-tl
  (translate [(+ (/ mount-width -2) post-adj) (- (/ mount-height  1.15) post-adj) 0] web-post))
(def thumb-post-bl
  (translate [(+ (/ mount-width -2) post-adj) (+ (/ mount-height -1.15) post-adj) 0] web-post))
(def thumb-post-br
  (translate [(- (/ mount-width 2) post-adj)  (+ (/ mount-height -1.15) post-adj) 0] web-post))

(defn thumb-connectors [c]
  (let [minidox-style? (get c :configuration-minidox-style?)
        row-count (get c :configuration-last-row-count)
        lastrow (flastrow (get c :configuration-nrows))
        cornerrow (fcornerrow (get c :configuration-nrows))]
    (if minidox-style?
      (union
       (triangle-hulls    ; top two
        (thumb-tl-place c thumb-post-tr)
        (thumb-tl-place c thumb-post-br)
        (thumb-tr-place c thumb-post-tl)
        (thumb-tr-place c thumb-post-bl)
        (thumb-tl-place c thumb-post-br)
        (thumb-tl-place c thumb-post-bl))
       (triangle-hulls    ; top two to the middle two, starting on the left
        (thumb-tl-place c thumb-post-tl)
        (thumb-ml-place c thumb-post-tr)
        (thumb-tl-place c thumb-post-bl)
        (thumb-ml-place c thumb-post-br))
       (triangle-hulls    ; top two to the main keyboard, starting on the left
        (thumb-tl-place c thumb-post-tl)
        (key-place c 0 cornerrow web-post-bl)
        (thumb-tl-place c thumb-post-tr)
        (key-place c 0 cornerrow web-post-br)
        (thumb-tr-place c thumb-post-tl)
        (key-place c 1 cornerrow web-post-bl)
        (thumb-tr-place c thumb-post-tr)
        (key-place c 1 cornerrow web-post-br)
        (thumb-tr-place c thumb-post-br)
        (key-place c 2 cornerrow web-post-bl)
        (case row-count
          :zero ()
          (key-place c 2 lastrow web-post-bl))
        (key-place c 2 (case row-count :zero cornerrow lastrow) web-post-bl)
        (key-place c 2 (case row-count :zero cornerrow lastrow) web-post-br)
        (thumb-tr-place c thumb-post-br)
        (key-place c 3 (case row-count :zero cornerrow lastrow) web-post-bl))
       (triangle-hulls
        (thumb-tl-place c thumb-post-bl)
        (thumb-ml-place c thumb-post-br)
        (thumb-ml-place c thumb-post-bl))
       (triangle-hulls
        (key-place c 2 lastrow web-post-tl)
        (key-place c 2 cornerrow web-post-bl)
        (key-place c 2 lastrow web-post-tr)
        (key-place c 2 cornerrow web-post-br)
        (key-place c 3 cornerrow web-post-bl))
       (triangle-hulls
        (key-place c 3 lastrow web-post-tr)
        (key-place c 4 cornerrow web-post-bl)))
      (union
       (triangle-hulls    ; top two
        (thumb-tl-place c thumb-post-tr)
        (thumb-tl-place c thumb-post-br)
        (thumb-tr-place c thumb-post-tl)
        (thumb-tr-place c thumb-post-bl))
       (triangle-hulls    ; bottom two on the right
        (thumb-br-place c web-post-tr)
        (thumb-br-place c web-post-br)
        (thumb-mr-place c web-post-tl)
        (thumb-mr-place c web-post-bl))
       (triangle-hulls    ; bottom two on the left
        (thumb-bl-place c web-post-tr)
        (thumb-bl-place c web-post-br)
        (thumb-ml-place c web-post-tl)
        (thumb-ml-place c web-post-bl))
       (triangle-hulls    ; centers of the bottom four
        (thumb-br-place c web-post-tl)
        (thumb-bl-place c web-post-bl)
        (thumb-br-place c web-post-tr)
        (thumb-bl-place c web-post-br)
        (thumb-mr-place c web-post-tl)
        (thumb-ml-place c web-post-bl)
        (thumb-mr-place c web-post-tr)
        (thumb-ml-place c web-post-br))
       (triangle-hulls    ; top two to the middle two, starting on the left
        (thumb-tl-place c thumb-post-tl)
        (thumb-ml-place c web-post-tr)
        (thumb-tl-place c thumb-post-bl)
        (thumb-ml-place c web-post-br)
        (thumb-tl-place c thumb-post-br)
        (thumb-mr-place c web-post-tr)
        (thumb-tr-place c thumb-post-bl)
        (thumb-mr-place c web-post-br)
        (thumb-tr-place c thumb-post-br))
       (triangle-hulls    ; top two to the main keyboard, starting on the left
        (thumb-tl-place c thumb-post-tl)
        (key-place c 0 cornerrow web-post-bl)
        (thumb-tl-place c thumb-post-tr)
        (key-place c 0 cornerrow web-post-br)
        (thumb-tr-place c thumb-post-tl)
        (key-place c 1 cornerrow web-post-bl)
        (thumb-tr-place c thumb-post-tr)
        (key-place c 1 cornerrow web-post-br)
        (thumb-tr-place c thumb-post-br)
        (key-place c 2 cornerrow web-post-bl)
        (case row-count
          :zero ()
          (key-place c 2 lastrow web-post-bl))
        (key-place c 2 (case row-count :zero cornerrow lastrow) web-post-bl)
        (key-place c 2 (case row-count :zero cornerrow lastrow) web-post-br)
        (thumb-tr-place c thumb-post-br)
        (key-place c 3 (case row-count :zero cornerrow lastrow) web-post-bl))
       (triangle-hulls
        (key-place c 1 cornerrow web-post-br)
        (key-place c 2 lastrow web-post-tl)
        (key-place c 2 cornerrow web-post-bl)
        (key-place c 2 lastrow web-post-tr)
        (key-place c 2 cornerrow web-post-br)
        (key-place c 3 cornerrow web-post-bl))))))

;;;;;;;;;;
;; Case ;;
;;;;;;;;;;

(def left-wall-x-offset 10)
(def left-wall-z-offset  3)

(defn left-key-position [c row direction]
  (map -
       (key-position c 0 row [(* mount-width -0.5) (* direction mount-height 0.5) 0])
       [left-wall-x-offset 0 left-wall-z-offset]))

(defn inner-key-position [c row direction]
  (map -
       (key-position c -1 row [(* mount-width -0.5) (* direction mount-height 0.5) 0])
       [left-wall-x-offset 0 left-wall-z-offset]))

(defn left-key-place [c row direction shape]
  (translate (left-key-position c row direction) shape))

(defn inner-key-place [c row direction shape]
  (translate (inner-key-position c row direction) shape))

(defn wall-locate1 [dx dy]
  [(* dx wall-thickness) (* dy wall-thickness) -1])
(defn wall-locate2 [dx dy]
  [(* dx wall-xy-offset) (* dy wall-xy-offset) wall-z-offset])
(defn wall-locate3 [dx dy]
  [(* dx (+ wall-xy-offset wall-thickness))
   (* dy (+ wall-xy-offset wall-thickness))
   wall-z-offset])

(defn wall-brace
  "If you want to change the wall, use this.
   place1 means the location at the keyboard, marked by key-place or thumb-xx-place
   dx1 means the movement from place1 in x coordinate, multiplied by wall-xy-locate.
   dy1 means the movement from place1 in y coordinate, multiplied by wall-xy-locate.
   post1 means the position this wall attached to place1.
         xxxxx-br means bottom right of the place1.
         xxxxx-bl means bottom left of the place1.
         xxxxx-tr means top right of the place1.
         xxxxx-tl means top left of the place1.
   place2 means the location at the keyboard, marked by key-place or thumb-xx-place
   dx2 means the movement from place2 in x coordinate, multiplied by wall-xy-locate.
   dy2 means the movement from place2 in y coordinate, multiplied by wall-xy-locate.
   post2 means the position this wall attached to place2.
         xxxxx-br means bottom right of the place2.
         xxxxx-bl means bottom left of the place2.
         xxxxx-tr means top right of the place2.
         xxxxx-tl means top left of the place2.
   How does it work?
   Given the following wall
       a ==\\ b
            \\
           c \\ d
             | |
             | |
             | |
             | |
           e | | f
   In this function a: usually the wall of a switch hole.
                    b: the result of hull and translation from wall-locate1
                    c: the result of hull and translation from wall-locate2
                    d: the result of hull and translation from wall-locate3
                    e: the result of bottom-hull translation from wall-locate2
                    f: the result of bottom-hull translation from wall-locate3"
  [place1 dx1 dy1 post1 place2 dx2 dy2 post2]
  (union
   (hull
    (place1 post1)
    (place1 (translate (wall-locate1 dx1 dy1) post1))
    (place1 (translate (wall-locate2 dx1 dy1) post1))
    (place1 (translate (wall-locate3 dx1 dy1) post1))
    (place2 post2)
    (place2 (translate (wall-locate1 dx2 dy2) post2))
    (place2 (translate (wall-locate2 dx2 dy2) post2))
    (place2 (translate (wall-locate3 dx2 dy2) post2)))
   (bottom-hull
    (place1 (translate (wall-locate2 dx1 dy1) post1))
    (place1 (translate (wall-locate3 dx1 dy1) post1))
    (place2 (translate (wall-locate2 dx2 dy2) post2))
    (place2 (translate (wall-locate3 dx2 dy2) post2)))))

(defn key-wall-brace [c x1 y1 dx1 dy1 post1 x2 y2 dx2 dy2 post2]
  (wall-brace (partial key-place c x1 y1) dx1 dy1 post1
              (partial key-place c x2 y2) dx2 dy2 post2))

(defn right-wall [c]
  (let [row-count (get c :configuration-last-row-count)
        use-wide-pinky? (get c :configuration-use-wide-pinky?)
        lastcol (flastcol (get c :configuration-ncols))
        lastrow (flastrow (get c :configuration-nrows))
        cornerrow (fcornerrow (get c :configuration-nrows))]
    (union (key-wall-brace c
                           lastcol 0 0 1 (wide-post-tr use-wide-pinky?)
                           lastcol 0 1 0 (wide-post-tr use-wide-pinky?))
           (for [y (range 0 lastrow)]
             (key-wall-brace c
                             lastcol y 1 0 (wide-post-tr use-wide-pinky?)
                             lastcol y 1 0 (wide-post-br use-wide-pinky?)))
           (case row-count
             :full (key-wall-brace c
                             lastcol lastrow 1 0 (wide-post-tr use-wide-pinky?)
                             lastcol lastrow 1 0 (wide-post-br use-wide-pinky?))
             ())
           (for [y (range 1 lastrow)]
             (key-wall-brace c
                             lastcol (dec y) 1 0 (wide-post-br use-wide-pinky?)
                             lastcol y 1 0 (wide-post-tr use-wide-pinky?)))
           (case row-count
             :full (key-wall-brace c
                             lastcol (dec lastrow) 1 0 (wide-post-br use-wide-pinky?)
                             lastcol lastrow       1 0 (wide-post-tr use-wide-pinky?))
             ())
           (key-wall-brace c
                           lastcol (case row-count :full lastrow cornerrow) 0 -1 (wide-post-br use-wide-pinky?)
                           lastcol (case row-count :full lastrow cornerrow) 1  0 (wide-post-br use-wide-pinky?)))))

(defn back-wall [c]
  (let [ncols (get c :configuration-ncols)
        lastcol (flastcol ncols)
        use-inner-column? (get c :configuration-use-inner-column?)]
  (union
  (for [x (range (if use-inner-column? -1 0) ncols)]
    (key-wall-brace c x 0 0 1 web-post-tl x       0 0 1 web-post-tr))
  (for [x (range (if use-inner-column?  0 1) ncols)]
    (key-wall-brace c x 0 0 1 web-post-tl (dec x) 0 0 1 web-post-tr))
  (key-wall-brace c lastcol 0 0 1 web-post-tr lastcol 0 1 0 web-post-tr))))

(defn left-wall [c]
  (let [nrows (get c :configuration-nrows)
        lastrow (flastrow nrows)
        cornerrow (fcornerrow nrows)
        use-inner-column? (get c :configuration-use-inner-column?)]
    (union
    (for [y (range 0 (if use-inner-column? cornerrow lastrow))]
      (union
       (wall-brace (partial (if use-inner-column?
                              (partial inner-key-place c)
                              (partial left-key-place c))
                            y  1) -1 0 web-post
                   (partial (if use-inner-column?
                              (partial inner-key-place c)
                              (partial left-key-place c))
                            y -1) -1 0 web-post)
       (hull (key-place c (if use-inner-column? -1 0) y web-post-tl)
             (key-place c (if use-inner-column? -1 0) y web-post-bl)
             ((if use-inner-column?
                (partial inner-key-place c)
                (partial left-key-place c))
              y  1 web-post)
             ((if use-inner-column?
                (partial inner-key-place c)
                (partial left-key-place c))
              y -1 web-post))))
    (for [y (range 1 (if use-inner-column? cornerrow lastrow))]
      (union
       (wall-brace (partial (if use-inner-column?
                              (partial inner-key-place c)
                              (partial left-key-place c))
                            (dec y) -1) -1 0 web-post
                   (partial (if use-inner-column?
                              (partial inner-key-place c)
                              (partial left-key-place c))
                            y        1) -1 0 web-post)
       (hull (key-place c (if use-inner-column? -1 0) y       web-post-tl)
             (key-place c (if use-inner-column? -1 0) (dec y) web-post-bl)
             ((if use-inner-column?
                (partial inner-key-place c)
                (partial left-key-place c))
              y        1 web-post)
             ((if use-inner-column?
                (partial inner-key-place c)
                (partial left-key-place c)) (dec y) -1 web-post))))
    (wall-brace (partial key-place c (if use-inner-column? -1 0) 0) 0 1 web-post-tl
                (partial (if use-inner-column?
                           (partial inner-key-place c)
                           (partial left-key-place c)) 0 1)  0 1 web-post)
    (wall-brace (partial (if use-inner-column?
                           (partial inner-key-place c)
                           (partial left-key-place c)) 0 1)  0 1 web-post
                (partial (if use-inner-column?
                           (partial inner-key-place c)
                           (partial left-key-place c)) 0 1) -1 0 web-post))))
(defn front-wall [c]
  (let [ncols (get c :configuration-ncols)
        nrows (get c :configuration-nrows)
        lastrow (flastrow nrows)
        cornerrow (fcornerrow nrows)
        row-count (get c :configuration-last-row-count)]
    (union
     (key-wall-brace c
                     3 (case row-count :zero cornerrow lastrow) 0   -1 web-post-bl
                     3 (case row-count :zero cornerrow lastrow) 0.5 -1 web-post-br)
     (key-wall-brace c
                     3 (case row-count :zero cornerrow lastrow)   0.5 -1 web-post-br
                     4 (case row-count :full lastrow   cornerrow) 0   -1 web-post-bl)
     (for [x (range 4 ncols)]
       (key-wall-brace c
                       x (case row-count :full lastrow cornerrow) 0 -1 web-post-bl
                       x (case row-count :full lastrow cornerrow) 0 -1 web-post-br))
     (for [x (range 5 ncols)]
       (key-wall-brace c
                       x       (case row-count :full lastrow cornerrow) 0 -1 web-post-bl
                       (dec x) (case row-count :full lastrow cornerrow) 0 -1 web-post-br)))))

(defn pinky-connectors [c]
  (let [row-count (get c :configuration-last-row-count)
        use-wide-pinky? (get c :configuration-use-wide-pinky?)
        lastcol (flastcol (get c :configuration-ncols))
        lastrow (flastrow (get c :configuration-nrows))
        cornerrow (fcornerrow (get c :configuration-nrows))]
    (if-not use-wide-pinky?
      ()
      (apply union
             (concat
              (for [row (range 0 (case row-count :full (inc lastrow) lastrow))]
                (triangle-hulls
                 (key-place c lastcol row web-post-tr)
                 (key-place c lastcol row (wide-post-tr use-wide-pinky?))
                 (key-place c lastcol row web-post-br)
                 (key-place c lastcol row (wide-post-br use-wide-pinky?))))
              (for [row (range 0 (case row-count :full lastrow cornerrow))]
                (triangle-hulls
                 (key-place c lastcol row       web-post-br)
                 (key-place c lastcol row       (wide-post-br use-wide-pinky?))
                 (key-place c lastcol (inc row) web-post-tr)
                 (key-place c lastcol (inc row) (wide-post-tr use-wide-pinky?)))))))))

(defn pinky-wall [c]
  (let [row-count (get c :configuration-last-row-count)
        use-wide-pinky? (get c :configuration-use-wide-pinky?)
        lastcol (flastcol (get c :configuration-ncols))
        lastrow (flastrow (get c :configuration-nrows))
        cornerrow (fcornerrow (get c :configuration-nrows))]
    (if-not use-wide-pinky?
      ()
      (union
       (key-wall-brace c
                       lastcol (case row-count :full lastrow cornerrow) 0 -1 web-post-br
                       lastcol (case row-count :full lastrow cornerrow) 0 -1 (wide-post-br use-wide-pinky?))
       (key-wall-brace c
                       lastcol 0 0 1 web-post-tr
                       lastcol 0 0 1 (wide-post-tr use-wide-pinky?))))))

(defn thumb-wall [c]
  (let [minidox-style? (get c :configuration-minidox-style?)]
    (if minidox-style?
      (union
       (wall-brace (partial thumb-ml-place c)  0  1 thumb-post-tr
                   (partial thumb-ml-place c)  0  1 thumb-post-tl)
       (wall-brace (partial thumb-tr-place c)  0 -1 thumb-post-br
                   (partial thumb-tr-place c)  0 -2 thumb-post-bl)
       (wall-brace (partial thumb-tr-place c)  0 -2 thumb-post-bl
                   (partial thumb-tl-place c)  0 -2 thumb-post-bl)
       (wall-brace (partial thumb-tl-place c)  0 -2 thumb-post-bl
                   (partial thumb-ml-place c) -1 -1 thumb-post-bl))
      (union
       (wall-brace (partial thumb-mr-place c)  0   -1 web-post-br
                   (partial thumb-tr-place c)  0   -1 thumb-post-br)
       (wall-brace (partial thumb-mr-place c)  0   -1 web-post-br
                   (partial thumb-mr-place c)  0   -1 web-post-bl)
       (wall-brace (partial thumb-br-place c)  0   -1 web-post-br
                   (partial thumb-br-place c)  0   -1 web-post-bl)
       (wall-brace (partial thumb-ml-place c) -0.3  1 web-post-tr
                   (partial thumb-ml-place c)  0    1 web-post-tl)
       (wall-brace (partial thumb-bl-place c)  0    1 web-post-tr
                   (partial thumb-bl-place c)  0    1 web-post-tl)
       (wall-brace (partial thumb-br-place c) -1    0 web-post-tl
                   (partial thumb-br-place c) -1    0 web-post-bl)
       (wall-brace (partial thumb-bl-place c) -1    0 web-post-tl
                   (partial thumb-bl-place c) -1    0 web-post-bl)))))

(defn thumb-corner [c]
  (let [minidox-style? (get c :configuration-minidox-style?)]
     (if minidox-style?
       (union (wall-brace (partial thumb-ml-place c) -1  0 thumb-post-tl (partial thumb-ml-place c) -1  0 thumb-post-bl)
              (wall-brace (partial thumb-ml-place c) -1  0 thumb-post-bl (partial thumb-ml-place c) -1 -1 thumb-post-bl)
              (wall-brace (partial thumb-ml-place c) -1  0 thumb-post-tl (partial thumb-ml-place c)  0  1 thumb-post-tl))
       (union (wall-brace (partial thumb-br-place c) -1  0 web-post-bl   (partial thumb-br-place c)  0 -1 web-post-bl)
              (wall-brace (partial thumb-bl-place c) -1  0 web-post-tl   (partial thumb-bl-place c)  0  1 web-post-tl)))))

(defn thumb-tweener [c]
  (let [minidox-style? (get c :configuration-minidox-style?)
        row-count (get c :configuration-last-row-count)
        nrows (get c :configuration-nrows)
        lastrow (flastrow nrows)
        cornerrow (fcornerrow nrows)]
    (union
     (wall-brace (partial thumb-tr-place c)  0 -1 thumb-post-br
                 (partial (partial key-place c) 3 (case row-count :zero cornerrow lastrow))  0 -1 web-post-bl)
     (if-not minidox-style?
       (union
        (wall-brace (partial thumb-mr-place c)  0 -1 web-post-bl  (partial thumb-br-place c)  0 -1 web-post-br)
        (wall-brace (partial thumb-ml-place c)  0  1 web-post-tl  (partial thumb-bl-place c)  0  1 web-post-tr)
        (wall-brace (partial thumb-bl-place c) -1  0 web-post-bl  (partial thumb-br-place c) -1  0 web-post-tl))
       ()))))

(defn second-thumb-to-body [c]
  (let [minidox-style? (get c :configuration-minidox-style?)
        use-inner-column? (get c :configuration-use-inner-column?)
        nrows (get c :configuration-nrows)
        cornerrow (fcornerrow nrows)
        middlerow (fmiddlerow nrows)]
    (union
     (bottom-hull
      (if use-inner-column?
        (inner-key-place c middlerow -1 (translate (wall-locate2 -1 0) web-post))
        (left-key-place  c cornerrow -1 (translate (wall-locate2 -1 0) web-post)))
      (if use-inner-column?
        (inner-key-place c middlerow -1 (translate (wall-locate3 -1 0) web-post))
        (left-key-place  c cornerrow -1 (translate (wall-locate3 -1 0) web-post)))
      (thumb-ml-place c (translate (wall-locate2 -0.3 1) (if minidox-style? thumb-post-tr web-post-tr)))
      (thumb-ml-place c (translate (wall-locate3 -0.3 1) (if minidox-style? thumb-post-tr web-post-tr))))
     (hull
      (if use-inner-column?
        (inner-key-place c middlerow -1 (translate (wall-locate2 -1 0) web-post))
        (left-key-place  c cornerrow -1 (translate (wall-locate2 -1 0) web-post)))
      (if use-inner-column?
        (inner-key-place c middlerow -1 (translate (wall-locate3 -1 0) web-post))
        (left-key-place  c cornerrow -1 (translate (wall-locate3 -1 0) web-post)))
      (thumb-ml-place c (translate (wall-locate2 -0.3 1) (if minidox-style? thumb-post-tr web-post-tr)))
      (thumb-ml-place c (translate (wall-locate3 -0.3 1) (if minidox-style? thumb-post-tr web-post-tr)))
      (thumb-tl-place c thumb-post-tl))
     (if use-inner-column?
       (hull
        (inner-key-place c middlerow -1 web-post)
        (inner-key-place c middlerow -1 (translate (wall-locate1 -1 0) web-post))
        (inner-key-place c middlerow -1 (translate (wall-locate2 -1 0) web-post))
        (inner-key-place c middlerow -1 (translate (wall-locate3 -1 0) web-post))
        (thumb-tl-place c thumb-post-tl))
       (hull
        (left-key-place c cornerrow -1 web-post)
        (left-key-place c cornerrow -1 (translate (wall-locate1 -1 0) web-post))
        (left-key-place c cornerrow -1 (translate (wall-locate2 -1 0) web-post))
        (left-key-place c cornerrow -1 (translate (wall-locate3 -1 0) web-post))
        (thumb-tl-place c thumb-post-tl)))
     (if use-inner-column?
       (hull
        (inner-key-place c middlerow -1 web-post)
        (inner-key-place c middlerow -1 (translate (wall-locate1 -1 0) web-post))
        (key-place c -1 middlerow web-post-bl)
        (key-place c -1 middlerow (translate (wall-locate1 -1 0) web-post-bl))
        (thumb-tl-place c thumb-post-tl))
       (hull
        (left-key-place c cornerrow -1 web-post)
        (left-key-place c cornerrow -1 (translate (wall-locate1 -1 0) web-post))
        (key-place c 0 cornerrow web-post-bl)
        (key-place c 0 cornerrow (translate (wall-locate1 -1 0) web-post-bl))
        (thumb-tl-place c thumb-post-tl)))
     (if use-inner-column?
       (triangle-hulls
        (thumb-tl-place c thumb-post-tl)
        (key-place c  0 cornerrow web-post-bl)
        (key-place c -1 middlerow web-post-bl)
        (key-place c -1 cornerrow web-post-tr))
       ())
     (hull
      (thumb-ml-place c (if minidox-style? thumb-post-tr web-post-tr))
      (thumb-ml-place c (translate (wall-locate1 -0.3 1) (if minidox-style? thumb-post-tr web-post-tr)))
      (thumb-ml-place c (translate (wall-locate2 -0.3 1) (if minidox-style? thumb-post-tr web-post-tr)))
      (thumb-ml-place c (translate (wall-locate3 -0.3 1) (if minidox-style? thumb-post-tr web-post-tr)))
      (thumb-tl-place c thumb-post-tl)))))

(defn case-walls [c]
  (union
   (back-wall c)
   (right-wall c)
   (left-wall c)
   (front-wall c)
   (pinky-wall c)
   (pinky-connectors c)
   (thumb-wall c)
   (thumb-corner c)
   (thumb-tweener c)
   (second-thumb-to-body c)))

(defn frj9-start [c]
  (map + [0 -3  0] (key-position c 0 0 (map + (wall-locate3 0 1) [0 (/ mount-height  2) 0]))))

(defn fusb-holder-position [c]
  (key-position c 1 0 (map + (wall-locate2 0 1) [0 (/ mount-height 2) 0])))

(defn trrs-usb-holder-ref [c]
  (key-position c 0 0 (map - (wall-locate2  0  -1) [0 (/ mount-height 2) 0])))

(defn trrs-usb-holder-position [c]
  (map + [17 19.3 0] [(first (trrs-usb-holder-ref c)) (second (trrs-usb-holder-ref c)) 2]))
(def trrs-usb-holder-cube
  (cube 15 12 2))
(defn trrs-usb-holder-space [c]
  (translate (map + (trrs-usb-holder-position c) [0 (* -1 wall-thickness) 1]) trrs-usb-holder-cube))
(defn trrs-usb-holder-holder [c]
  (translate (trrs-usb-holder-position c) (cube 19 12 4)))

(defn trrs-usb-jack [c] (translate (map + (trrs-usb-holder-position c) [0 10 3]) (cube 8.1 20 3.1)))

(def trrs-holder-size [6.2 10 2]) ; trrs jack PJ-320A
(def trrs-holder-hole-size [6.2 10 6]) ; trrs jack PJ-320A
(defn trrs-holder-position [c]
  (map + (trrs-usb-holder-position c) [-13.6 0 0]))
(def trrs-holder-thickness 2)
(def trrs-holder-thickness-2x (* 2 trrs-holder-thickness))
(defn trrs-holder [c]
  (union
   (->> (cube (+ (first trrs-holder-size) trrs-holder-thickness-2x)
              (+ trrs-holder-thickness (second trrs-holder-size))
              (+ (last trrs-holder-size) trrs-holder-thickness))
        (translate [(first (trrs-holder-position c))
                    (second (trrs-holder-position c))
                    (/ (+ (last trrs-holder-size) trrs-holder-thickness) 2)]))))
(defn trrs-holder-hole [c]
  (union
   (->>
    (->> (binding [*fn* 30] (cylinder 2.55 20))) ; 5mm trrs jack
    (rotate (deg2rad  90) [1 0 0])
    (translate [(first (trrs-holder-position c))
                (+ (second (trrs-holder-position c))
                   (/ (+ (second trrs-holder-size) trrs-holder-thickness) 2))
                (+ 3 (/ (+ (last trrs-holder-size) trrs-holder-thickness) 2))])) ;1.5 padding
  ; rectangular trrs holder
   (->> (apply cube trrs-holder-hole-size)
        (translate [(first (trrs-holder-position c))
                    (+ (/ trrs-holder-thickness -2) (second (trrs-holder-position c)))
                    (+ (/ (last trrs-holder-hole-size) 2) trrs-holder-thickness)]))))

(defn pro-micro-position [c]
  (map + (key-position c 0 0.15 (wall-locate3 -1 0)) [-2 2 -30]))
(def pro-micro-space-size [4 10 12]) ; z has no wall;
(def pro-micro-wall-thickness 2)
(def pro-micro-holder-size
  [(+ pro-micro-wall-thickness (first pro-micro-space-size))
   (+ pro-micro-wall-thickness (second pro-micro-space-size))
   (last pro-micro-space-size)])
(defn pro-micro-space [c]
  (->> (cube (first pro-micro-space-size)
             (second pro-micro-space-size)
             (last pro-micro-space-size))
       (translate [(- (first (pro-micro-position c)) (/ pro-micro-wall-thickness 2))
                   (- (second (pro-micro-position c)) (/ pro-micro-wall-thickness 2))
                   (last (pro-micro-position c))])))
(defn pro-micro-holder [c]
  (difference
   (->> (cube (first pro-micro-holder-size)
              (second pro-micro-holder-size)
              (last pro-micro-holder-size))
        (translate [(first (pro-micro-position c))
                    (second (pro-micro-position c))
                    (last (pro-micro-position c))]))
   (pro-micro-space c)))

(def teensy-width 20)
(def teensy-height 12)
(def teensy-length 33)
(def teensy2-length 53)
(def teensy-pcb-thickness 2)
(def teensy-holder-width  (+ 7 teensy-pcb-thickness))
(def teensy-holder-height (+ 6 teensy-width))
(def teensy-offset-height 5)
(def teensy-holder-top-length 18)
(defn teensy-top-xy [c]
  (key-position c 0 (- (fcenterrow (get c :configuration-nrows)) 1) (wall-locate3 -1 0)))
(defn teensy-bot-xy [c]
  (key-position c 0 (+ (fcenterrow (get c :configuration-nrows)) 1) (wall-locate3 -1 0)))
(defn teensy-holder-length [c]
  (- (second (teensy-top-xy c)) (second (teensy-bot-xy c))))
(defn teensy-holder-offset [c]
  (/ (teensy-holder-length c) -2))
(defn teensy-holder-top-offset [c]
  (- (/ teensy-holder-top-length 2) (teensy-holder-length c)))

(defn teensy-holder [c]
  (->>
   (union
    (->> (cube 3 (teensy-holder-length c) (+ 6 teensy-width))
         (translate [1.5 (teensy-holder-offset c) 0]))
    (->> (cube teensy-pcb-thickness (teensy-holder-length c) 3)
         (translate [(+ (/ teensy-pcb-thickness 2) 3) (teensy-holder-offset c) (- -1.5 (/ teensy-width 2))]))
    (->> (cube 4 (teensy-holder-length c) 4)
         (translate [(+ teensy-pcb-thickness 5) (teensy-holder-offset c) (-  -1 (/ teensy-width 2))]))
    (->> (cube teensy-pcb-thickness teensy-holder-top-length 3)
         (translate [(+ (/ teensy-pcb-thickness 2) 3) (teensy-holder-top-offset c) (+ 1.5 (/ teensy-width 2))]))
    (->> (cube 4 teensy-holder-top-length 4)
         (translate [(+ teensy-pcb-thickness 5) (teensy-holder-top-offset c) (+ 1 (/ teensy-width 2))])))
   (translate [(- teensy-holder-width) 0 0])
   (translate [-1.4 0 0])
   (translate [(first (teensy-top-xy c))
               (- (second (teensy-top-xy c)) 1)
               (/ (+ 6 teensy-width) 2)])))

(defn screw-insert [c column row bottom-radius top-radius height]
  (let [lastcol (flastcol (get c :configuration-ncols))
        lastrow (flastrow (get c :configuration-nrows))
        shift-right (= column lastcol)
        shift-left  (= column 0)
        shift-up    (and (not (or shift-right shift-left)) (= row 0))
        shift-down  (and (not (or shift-right shift-left)) (>= row lastrow))
        position    (if shift-up
                      (key-position c column row (map + (wall-locate2  0  1) [0 (/ mount-height 2) 0]))
                      (if shift-down
                        (key-position c column row (map - (wall-locate2  0 -1) [0 (/ mount-height 2) 0]))
                        (if shift-left
                          (map + (left-key-position c row 0) (wall-locate3 -1 0))
                          (key-position c column row (map + (wall-locate2  1  0) [(/ mount-width 2) 0 0])))))]
    (->> (screw-insert-shape bottom-radius top-radius height)
         (translate [(first position) (second position) (/ height 2)]))))

(defn screw-placement [c bottom-radius top-radius height]
  (let [use-wide-pinky? (get c :configuration-use-wide-pinky?)
        use-inner-column? (get c :configuration-use-inner-column?)
        lastcol (flastcol (get c :configuration-ncols))
        lastrow (flastrow (get c :configuration-nrows))
        lastloc (if-not use-wide-pinky? (+ lastcol 0.1) (+ lastcol 0.5))]
    (union (screw-insert c (if use-inner-column? -1 0) 0               bottom-radius top-radius height)
           (screw-insert c (if use-inner-column? -1 0) (- lastrow 0.8) bottom-radius top-radius height)
           (screw-insert c 2                           (+ lastrow 0.2) bottom-radius top-radius height)
           (screw-insert c 3                           0               bottom-radius top-radius height)
           (screw-insert c lastloc                     1               bottom-radius top-radius height))))

(def wire-post-height 7)
(def wire-post-overhang 3.5)
(def wire-post-diameter 2.6)
(defn wire-post [c direction offset]
  (->> (union (translate [0 (* wire-post-diameter -0.5 direction) 0]
                         (cube wire-post-diameter wire-post-diameter wire-post-height))
              (translate [0 (* wire-post-overhang -0.5 direction) (/ wire-post-height -2)]
                         (cube wire-post-diameter wire-post-overhang wire-post-diameter)))
       (translate [0 (- offset) (+ (/ wire-post-height -2) 3)])
       (rotate (/ (get c :configuration-alpha) -2) [1 0 0])
       (translate [3 (/ mount-height -2) 0])))

(defn wire-posts [c]
  (union
   (thumb-ml-place c (translate [-5 0 -2]  (wire-post c  1 0)))
   (thumb-ml-place c (translate [0 0 -2.5] (wire-post c -1 6)))
   (thumb-ml-place c (translate [5 0 -2]   (wire-post c  1 0)))
   (for [column (range 0 (flastcol (get c :configuration-ncols)))
         row (range 0 (fcornerrow (get c :configuration-nrows)))]
     (union
      (key-place c column row (translate [-5 0 0] (wire-post c  1 0)))
      (key-place c column row (translate [0 0 0]  (wire-post c -1 6)))
      (key-place c column row (translate [5 0 0]  (wire-post c  1 0)))))))

(defn wrist-rest-locate [c]
  (let [nrows 5]
    (key-position c 3 8 (map + (wall-locate1 0 (- 4.9 (* 2 nrows))) [0 (/ mount-height 2) 0]))))

(defn wrest-wall-cut
  [c]
  (->> (for [xyz (range 1.00 10 3)]
         (union
          (translate [1, xyz,1] (case-walls c))))))

(defn wrist-rest-build [c]
  (difference
   (->> (union
         (->> (wrist-rest-base c)
              (translate [wrist-base-position-x -40 0])
              (rotate  (/ (* pi wrist-rest-rotation-angle) 180)  [0 0 1]))
         (->> (difference (rest-case-connectors c)
                          (rest-case-cuts c)
                          cut-bottom)))
        (translate [(+ (first (thumborigin c)) 33) (- (second (thumborigin c)) 50) 0]))
   (translate [(+ (first (thumborigin c)) 33)
               (- (second (thumborigin c)) 50)
               0]
              (rest-case-cuts c))
   (wrest-wall-cut c)))

(defn model-right [c]
  (let [use-inner-column? (get c :configuration-use-inner-column?)
        show-caps? (get c :configuration-show-caps?)
        use-promicro-usb-hole? (get c :configuration-use-promicro-usb-hole?)
        use-screw-inserts? (get c :configuration-use-screw-inserts?)
        use-trrs? (get c :configuration-use-trrs?)
        use-wire-post? (get c :configuration-use-wire-post?)
        use-wrist-rest? (get c :configuration-use-wrist-rest?)]
    (difference
     (union
      (if use-wrist-rest? (wrist-rest-build c) ())
      (if show-caps? (caps c) ())
      (if show-caps? (thumbcaps c) ())
      (if use-wire-post? (wire-posts c) ())
      (if-not use-trrs? (rj9-holder frj9-start c) ())
      (if use-inner-column? (inner-key-holes c) ())
      (key-holes c)
      (thumb c)
      (connectors c)
      (thumb-connectors c)
      (difference
       (union (case-walls c)
              (if use-screw-inserts?
                (screw-insert-outers screw-placement c)
                ())
              (if use-promicro-usb-hole?
                (union (pro-micro-holder c)
                       (trrs-usb-holder-holder c))
                (union (usb-holder fusb-holder-position c)
                       (pro-micro-holder c)))
              (if use-trrs?
                (trrs-holder c)
                ()))
       (if use-screw-inserts?
         (screw-insert-holes screw-placement c)
         ())
       (if use-trrs?
         (trrs-holder-hole c)
         (rj9-space frj9-start c))
       (if use-promicro-usb-hole?
         (union (trrs-usb-holder-space c)
                (trrs-usb-jack c))
         (usb-holder-hole fusb-holder-position c))))
     (translate [0 0 -60] (cube 350 350 120)))))

(defn model-left [c]
  (mirror [-1 0 0] (model-right c)))

(defn plate-right [c]
  (cut
   (translate [0 0 -0.1]
              (difference (union (case-walls c)
                                 (rj9-holder c)
                                 (usb-holder c)
                                 (screw-insert-outers c))
                          (translate [0 0 -10]
                                     (screw-insert-screw-holes screw-placement c))))))

(defn plate-left [c]
  (mirror [-1 0 0] (plate-right c)))

(def c {:configuration-nrows 4
        :configuration-ncols 5
        :configuration-create-side-nub? false
        :configuration-use-alps? false
        :configuration-minidox-style? false

        :configuration-alpha (/ pi 12)
        :configuration-beta (/ pi 36)
        :configuration-centercol 4
        :configuration-tenting-angle (/ pi 9)

        :configuration-use-promicro-usb-hole? false
        :configuration-use-trrs? false

        :configuration-use-hotswap? false
        :configuration-ortho? false
        :configuration-use-inner-column? false
        :configuration-keyboard-z-offset 4
        :configuration-show-caps? false
        :configuration-last-row-count :two
        :configuration-use-wide-pinky? false
        :configuration-use-wire-post? false
        :configuration-use-screw-inserts? true
        :configuration-use-wrist-rest? false})

#_(spit "things/right.scad"
      (write-scad (model-right c)))

#_(spit "things/right-plate.scad"
      (write-scad (right-plate c)))

#_(spit "things/right-plate.scad"
        (write-scad
         (cut
          (translate [0 0 -0.1]
                     (difference (union case-walls
                                        teensy-holder
                                          ; rj9-holder
                                        screw-insert-outers)
                                 (translate [0 0 -10] screw-insert-screw-holes))))))


#_(spit "things/left.scad"
        (write-scad (mirror [-1 0 0] model-right)))

#_(spit "things/right-test.scad"
        (write-scad
         (union
          key-holes
          connectors
          thumb
          thumb-connectors
          case-walls
          thumbcaps
          caps
          teensy-holder
          rj9-holder
          usb-holder-hole)))

#_(spit "things/test.scad"
        (write-scad
         (difference usb-holder usb-holder-hole)))

#_(defn -main [dum] 1)  ; dummy to make it easier to batc
