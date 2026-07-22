package com.vynex.gumshoos.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The geocoder's replies, including the shapes that must not reach the picker. */
class GeocodePlaceTest {

    @Test
    fun `reads the documented reply`() {
        val places = GeocodePlace.parse(
            """[{"name":"Bengaluru","lat":12.9767936,"lon":77.590082,
                 "country":"IN","state":"Karnataka"}]"""
        )
        assertEquals(1, places.size)
        assertEquals("Bengaluru", places[0].name)
        assertEquals(12.9767936, places[0].lat, 1e-7)
        assertEquals(77.590082, places[0].lon, 1e-7)
        assertEquals("Bengaluru, Karnataka, IN", places[0].label)
    }

    @Test
    fun `identical name, state and country collapse to the first`() {
        // The API really does return these — the same town from more than one source dataset, a
        // fraction of a degree apart. To a reader the rows are identical, so offering both asks a
        // question with no answer. First wins because results arrive best-match first.
        val places = GeocodePlace.parse(
            """[{"name":"London","lat":51.5073219,"lon":-0.1276474,"country":"GB","state":"England"},
                {"name":"London","lat":51.5085,"lon":-0.1257,"country":"GB","state":"England"},
                {"name":"London","lat":42.9832406,"lon":-81.243372,"country":"CA","state":"Ontario"}]"""
        )
        assertEquals(2, places.size)
        assertEquals(51.5073219, places[0].lat, 1e-7)
        assertEquals("London, Ontario, CA", places[1].label)
    }

    @Test
    fun `same name in different states is not a duplicate`() {
        // The reason the key is all three fields and not the name: these are real, distinct places
        // and collapsing them would silently pick one.
        val places = GeocodePlace.parse(
            """[{"name":"Springfield","lat":39.8,"lon":-89.6,"country":"US","state":"Illinois"},
                {"name":"Springfield","lat":37.2,"lon":-93.3,"country":"US","state":"Missouri"}]"""
        )
        assertEquals(2, places.size)
    }

    @Test
    fun `a place with no state still labels cleanly`() {
        val place = GeocodePlace.parse(
            """[{"name":"Singapore","lat":1.29,"lon":103.85,"country":"SG"}]"""
        ).single()
        assertEquals("Singapore, SG", place.label)
    }

    @Test
    fun `rows without coordinates are dropped, not defaulted to zero`() {
        // Lat 0, lon 0 is a real point in the Atlantic. A row missing them must not become it.
        val places = GeocodePlace.parse(
            """[{"name":"Nowhere","country":"XX"},
                {"name":"Bengaluru","lat":12.97,"lon":77.59,"country":"IN"}]"""
        )
        assertEquals(1, places.size)
        assertEquals("Bengaluru", places[0].name)
    }

    @Test
    fun `an unnamed row is dropped`() {
        assertTrue(GeocodePlace.parse("""[{"lat":1.0,"lon":2.0,"country":"XX"}]""").isEmpty())
    }

    @Test
    fun `no matches and error shapes come back empty`() {
        assertTrue(GeocodePlace.parse("[]").isEmpty())
        assertTrue(GeocodePlace.parse("").isEmpty())
        assertTrue(GeocodePlace.parse("not json").isEmpty())
        // A bad key answers with an object, not an array — the shape changes entirely.
        assertTrue(GeocodePlace.parse("""{"cod":401,"message":"Invalid API key"}""").isEmpty())
    }
}
