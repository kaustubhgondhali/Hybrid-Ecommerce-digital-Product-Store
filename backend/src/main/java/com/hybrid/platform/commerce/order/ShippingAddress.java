package com.hybrid.platform.commerce.order;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

@Embeddable
public class ShippingAddress {

    @Column(name = "ship_name")
    private String name;

    @Column(name = "ship_phone")
    private String phone;

    @Column(name = "ship_line1")
    private String line1;

    @Column(name = "ship_line2")
    private String line2;

    @Column(name = "ship_city")
    private String city;

    @Column(name = "ship_state")
    private String state;

    @Column(name = "ship_postal_code")
    private String postalCode;

    @Column(name = "ship_country")
    private String country;

    protected ShippingAddress() {
    }

    public ShippingAddress(String name, String phone, String line1, String line2, String city, String state,
                           String postalCode, String country) {
        this.name = name;
        this.phone = phone;
        this.line1 = line1;
        this.line2 = line2;
        this.city = city;
        this.state = state;
        this.postalCode = postalCode;
        this.country = country;
    }

    public String getName() { return name; }
    public String getPhone() { return phone; }
    public String getLine1() { return line1; }
    public String getLine2() { return line2; }
    public String getCity() { return city; }
    public String getState() { return state; }
    public String getPostalCode() { return postalCode; }
    public String getCountry() { return country; }
}
