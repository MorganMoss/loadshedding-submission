package wethinkcode.places.router.route;

import io.javalin.apibuilder.EndpointGroup;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import org.jetbrains.annotations.NotNull;
import wethinkcode.places.PlaceNameService;
import wethinkcode.places.model.Place;

import java.util.List;
import java.util.Optional;

import static io.javalin.apibuilder.ApiBuilder.*;

public class PlaceController implements Route {

    /**
     * Gets a place by name
     */
    void getPlace(Context ctx){
        String name = ctx.pathParam("name");
        Optional<Place> place = PlaceNameService.svc.places.place(name);

        if (place.isPresent()){
            ctx.json(place.get());
            ctx.status(HttpStatus.FOUND);
        } else {
            ctx.status(HttpStatus.NOT_FOUND);
        }
    }

    void getPlacesInProvince(Context ctx){
        String province = ctx.pathParam("province");
        List<Place> places = PlaceNameService.svc.places.placesInProvince(province);

        if (places.size()>0){
            ctx.json(places);
            ctx.status(HttpStatus.FOUND);
        } else {
            ctx.status(HttpStatus.NOT_FOUND);
        }
    }

    void getPlacesInMunicipality(Context ctx){
        String municipality = ctx.pathParam("municipality");
        List<Place> places = PlaceNameService.svc.places.placesInMunicipality(municipality);

        if (places.size()>0){
            ctx.json(places);
            ctx.status(HttpStatus.FOUND);
        } else {
            ctx.status(HttpStatus.NOT_FOUND);
        }
    }


    @NotNull
    @Override
    public EndpointGroup getEndPoints() {
        return () -> {
            path("place", () -> {
                path("{name}", () -> {
                    get(this::getPlace);
                });
            });
            path("places", () -> {
                path("province/{province}", () -> {
                    get(this::getPlacesInProvince);
                });
                path("municipality/{municipality}", () -> {
                    get(this::getPlacesInMunicipality);
                });
            });
        };
    }
}
