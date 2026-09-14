package com.wellsetups.WellSell.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class PageTest {
  @Test
  void handlesEmptyExactAndPartialPages() {
    Page page = new Page(3, 8);
    assertEquals(16, page.offset());
    assertEquals(1, page.maximum(0));
    assertEquals(2, page.maximum(16));
    assertEquals(3, page.maximum(17));
  }

  @Test
  void validatesBoundariesAndAvoidsOverflow() {
    assertThrows(IllegalArgumentException.class, () -> new Page(0, 8));
    assertThrows(IllegalArgumentException.class, () -> new Page(1, 51));
    assertThrows(IllegalArgumentException.class, () -> new Page(Integer.MAX_VALUE, 50));
    assertEquals(49_999_950L, new Page(Page.MAX_PAGE, 50).offset());
    assertEquals(Page.MAX_PAGE, new Page(1, 8).maximum(Long.MAX_VALUE));
  }
}
