#pragma once

#include <vector>
#include <memory>

#include "Vector2.h"
#include "Matrix2D.h"
#include "Color.h"

enum ShapeType
{
  FRAME = 0,
  RECT,
  ELLIPSE,
  PATH,
  TEXT
};

enum PaintType
{
  COLOR = 0,
  IMAGE,
  PATTERN,
  LINEAR_GRADIENT,
  RADIAL_GRADIENT
};

struct MatrixTransform
{
  Matrix2D<float> concatenatedMatrix;
  Matrix2D<float> matrix;
};

struct Transform
{
  Vector2<float> position;
  Vector2<float> scale;
  float rotation;
};

struct Paint
{
  PaintType type;
};

struct Stroke
{
  float width;
  Paint paint;
};

struct Fill
{
  Paint paint;
};

struct Shape
{
  ShapeType type;
  Transform transform;
  std::shared_ptr<Shape> parent;
  std::vector<std::shared_ptr<Shape>> children;
  // std::vector<Stroke> strokes;
  // std::vector<Fill> fills;
};
