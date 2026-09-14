package com.wellsetups.WellSell.storage;

public record Page(int number, int size) {
  public static final int MAX_PAGE = 1_000_000;

  public Page {
    if (number < 1 || number > MAX_PAGE || size < 1 || size > 50) {
      throw new IllegalArgumentException("Page or page size is outside the supported range");
    }
  }

  public long offset() {
    return (long) (number - 1) * size;
  }

  public long maximum(long rows) {
    if (rows < 0) {
      throw new IllegalArgumentException("Negative row count");
    }
    return Math.max(1, Math.min(MAX_PAGE, rows / size + (rows % size == 0 ? 0 : 1)));
  }
}
