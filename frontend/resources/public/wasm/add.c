#include "int.h"

#define MAX_OPERATIONS 2048

typedef struct _operations {
  int32_t a, b, r;
} operations_t;

operations_t operations[MAX_OPERATIONS];

int32_t add(int32_t a, int32_t b) {
  return a + b;
}

void compute() {
  for (int32_t i = 0; i < MAX_OPERATIONS; i++) {
    operations[i].r = add(operations[i].a, operations[i].b);
  }
}
